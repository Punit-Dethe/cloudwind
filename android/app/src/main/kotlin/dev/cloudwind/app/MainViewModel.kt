package dev.cloudwind.app

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cloudwind.core.Pairing
import dev.cloudwind.core.WorkerClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderRow(
    val path: String,
    val count: Int,
    val homeOnly: Boolean,
    val pending: Int = 0,
    val uploaded: Int = 0,
    val synced: Int = 0,
    val totalBytes: Long = 0L,
    val displayName: String = FolderSelection.displayName(path),
    val selected: Boolean = true,
    val isSaf: Boolean = false,
    val pcFolder: String = FolderSelection.defaultPcFolder(path, displayName),
)

data class UiState(
    /** False until the first DB/pairing load finishes so we never flash the Pairing screen on cold start. */
    val loaded: Boolean = false,
    val paired: Boolean = false,
    val server: String = "",
    val counts: Counts = Counts(),
    val inboxBytes: Long = 0,
    val directEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val batteryGate: Boolean = true,
    val batteryMin: Int = 40,
    val bigMb: Int = 50,
    val newPhotoDelaySecs: Int = SettleWindow.DEFAULT_DELAY_SECS,
    /** The PC advertised direct sync at pairing, and whether the last pass found it on this network. */
    val directAvailable: Boolean = false,
    val pcOnNetwork: Boolean = false,
    val lastDirect: Int = 0,
    val lastCloud: Int = 0,
    /** Why some files are held back, e.g. "3 waiting for Wi-Fi". */
    val waiting: String? = null,
    /** Folders currently selected for backup (merged with live DB sync status). */
    val folders: List<FolderRow> = emptyList(),
    /** Auto-discovered photo/video folders on this phone not yet selected for backup. */
    val availableFolders: List<FolderRow> = emptyList(),
    val allFolders: Boolean = false,
    /** Persistent backup history with cached preview thumbnail references. */
    val history: List<HistoryItem> = emptyList(),
    /** Absolute path to `filesDir/thumbs` where cached 320px WebP previews live. */
    val thumbDirPath: String = "",
    /** Map of normalized folder relPath -> most recent cached WebP thumbnail filename. */
    val folderThumbs: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
    val lastError: String? = null,
    val inboxFull: Boolean = false,
    val notice: String? = null,
    val maxObjectBytes: Long = 0,
    val access: MediaAccess = MediaAccess.FULL,
    /** Epoch seconds of the last successful pass (0 = never) and of pairing. */
    val lastRunAt: Long = 0,
    val pairedAt: Long = 0,
    val nowSecs: Long = 0,
    val batteryUnrestricted: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val settings = Settings(app)
    private val db = PhotoDb(app)
    private val thumbBackfillBusy = AtomicBoolean(false)
    @Volatile private var cachedPairing: Pairing? = null
    @Volatile private var pairingChecked: Boolean = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Batches waiting for the user's system delete confirmation. */
    private val deleteBatches = ArrayDeque<List<Long>>()
    private var activeBatch: List<Long>? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            // Avoid repeating 5 hardware Keystore decrypts every 4s poll; only re-read on first load or pair/unpair.
            val pairing = if (pairingChecked) {
                cachedPairing
            } else {
                val p = if (store.hasPairing()) store.loadPairing() else null
                cachedPairing = p
                pairingChecked = true
                p
            }
            val counts = db.counts()
            val dbByPath = db.folderStats().associateBy { FolderSelection.norm(it.path) }
            val historyRows = db.historyList(HistoryLogic.MAX_THUMBS)
            val thumbDir = ThumbCache.dir(app).absolutePath
            val folderThumbs = LinkedHashMap<String, String>()
            for (item in historyRows) {
                val tf = item.thumbFile ?: continue
                val norm = FolderSelection.norm(item.relPath)
                if (norm.isNotEmpty() && norm !in folderThumbs) {
                    folderThumbs[norm] = tf
                }
            }
            val access = MediaAccess.current(app)
            val batteryUnrestricted = app.getSystemService(android.os.PowerManager::class.java)
                .isIgnoringBatteryOptimizations(app.packageName)

            // Fast first emit: unblocks the splash screen in ~20ms with real pairing, counts, and history
            // BEFORE scanning all device MediaStore folders.
            if (!_state.value.loaded) {
                _state.update {
                    it.copy(
                        loaded = true,
                        paired = pairing != null, server = pairing?.url ?: "", counts = counts,
                        inboxBytes = SyncRunner.lastInboxBytes,
                        directEnabled = settings.directEnabled,
                        wifiOnly = settings.wifiOnly, batteryGate = settings.batteryGate, batteryMin = settings.batteryMin, bigMb = settings.bigMb,
                        newPhotoDelaySecs = settings.newPhotoDelaySecs,
                        allFolders = settings.allFolders,
                        directAvailable = pairing?.lanFingerprint != null, pcOnNetwork = settings.pcOnNetwork,
                        lastDirect = settings.lastDirect, lastCloud = settings.lastCloud, waiting = settings.waiting,
                        history = historyRows,
                        thumbDirPath = thumbDir,
                        folderThumbs = folderThumbs,
                        lastError = settings.lastError,
                        inboxFull = settings.inboxFull, notice = settings.notice, maxObjectBytes = settings.maxObjectBytes,
                        access = access,
                        lastRunAt = settings.lastRunAt, pairedAt = settings.pairedAt,
                        nowSecs = System.currentTimeMillis() / 1000,
                        batteryUnrestricted = batteryUnrestricted,
                    )
                }
            }

            val discovered = MediaScanner.discoverDeviceFolders(app, settings)
            val discoveredByPath = discovered.associateBy { it.relPath }

            // Union of discovered device folders, explicitly selected folders, and folders with tracked DB rows.
            val allPaths = linkedSetOf<String>()
            discovered.forEach { allPaths += it.relPath }
            if (settings.hasExplicitFolderSelection) allPaths += settings.selectedFolders
            dbByPath.keys.filter { it.isNotEmpty() }.forEach { allPaths += it }

            val allRows = allPaths.map { normPath ->
                val found = discoveredByPath[normPath]
                val st = dbByPath[normPath]
                val sel = settings.isFolderSelected(normPath)
                val displayName = found?.displayName ?: FolderSelection.displayName(normPath)
                FolderRow(
                    path = normPath,
                    count = maxOf(found?.itemCount ?: 0, st?.total ?: 0),
                    homeOnly = settings.policyFor(normPath) == dev.cloudwind.core.Policy.HOME_ONLY,
                    pending = st?.pending ?: 0,
                    uploaded = st?.uploaded ?: 0,
                    synced = st?.synced ?: 0,
                    totalBytes = maxOf(found?.totalBytes ?: 0L, st?.totalBytes ?: 0L),
                    displayName = displayName,
                    selected = sel,
                    isSaf = found?.isSaf ?: (normPath in settings.safFolders()),
                    pcFolder = settings.pcFolderFor(normPath, displayName),
                )
            }
            val selectedRows = allRows.filter { it.selected }
                .sortedWith(compareByDescending<FolderRow> { it.path == "DCIM/Camera" }.thenByDescending { it.count }.thenBy { it.displayName.lowercase() })
            val availableRows = allRows.filter { !it.selected && it.count > 0 }
                .sortedWith(compareByDescending<FolderRow> { it.count }.thenBy { it.displayName.lowercase() })

            _state.update {
                it.copy(
                    loaded = true,
                    paired = pairing != null, server = pairing?.url ?: "", counts = counts,
                    inboxBytes = SyncRunner.lastInboxBytes,
                    directEnabled = settings.directEnabled,
                    wifiOnly = settings.wifiOnly, batteryGate = settings.batteryGate, batteryMin = settings.batteryMin, bigMb = settings.bigMb,
                    newPhotoDelaySecs = settings.newPhotoDelaySecs,
                    allFolders = settings.allFolders,
                    directAvailable = pairing?.lanFingerprint != null, pcOnNetwork = settings.pcOnNetwork,
                    lastDirect = settings.lastDirect, lastCloud = settings.lastCloud, waiting = settings.waiting,
                    folders = selectedRows,
                    availableFolders = availableRows,
                    history = historyRows,
                    thumbDirPath = thumbDir,
                    folderThumbs = folderThumbs,
                    lastError = settings.lastError,
                    inboxFull = settings.inboxFull, notice = settings.notice, maxObjectBytes = settings.maxObjectBytes,
                    access = access,
                    lastRunAt = settings.lastRunAt, pairedAt = settings.pairedAt,
                    nowSecs = System.currentTimeMillis() / 1000,
                    batteryUnrestricted = batteryUnrestricted,
                )
            }

            // Lazily backfill thumbnails for any pre-v6 upgraded history items still on the phone.
            val missing = historyRows.filter { it.thumbFile == null && !it.localRemoved }.take(12)
            if (missing.isNotEmpty() && thumbBackfillBusy.compareAndSet(false, true)) {
                try {
                    var generated = 0
                    for (item in missing) {
                        val tf = ThumbCache.ensureThumbnail(app, item.objectId, item.mediaId, item.isVideo, item.docUri)
                        val dur = if (item.isVideo && item.durationMs <= 0L) {
                            ThumbCache.queryDurationMs(app.contentResolver, item.mediaId, true, item.docUri)
                        } else item.durationMs
                        if (tf != null || dur != item.durationMs) {
                            db.setHistoryThumb(item.id, tf, dur)
                            if (tf != null) generated++
                        }
                    }
                    if (generated > 0) {
                        ThumbCache.prune(app, db)
                        val updatedHistory = db.historyList(HistoryLogic.MAX_THUMBS)
                        val updatedFolderThumbs = LinkedHashMap<String, String>()
                        for (item in updatedHistory) {
                            val tf = item.thumbFile ?: continue
                            val norm = FolderSelection.norm(item.relPath)
                            if (norm.isNotEmpty() && norm !in updatedFolderThumbs) updatedFolderThumbs[norm] = tf
                        }
                        _state.update { it.copy(history = updatedHistory, folderThumbs = updatedFolderThumbs) }
                    }
                } finally {
                    thumbBackfillBusy.set(false)
                }
            }
        }
    }

    fun message(text: String?) = _state.update { it.copy(message = text) }

    /** Called with the text of a scanned or pasted pairing code. */
    fun pair(text: String) {
        val p = Pairing.parse(text)
        if (p == null) { message("That doesn't look like a Cloudwind pairing code."); return }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { WorkerClient(p.url, p.token).checkAccess() }
            if (!ok) {
                _state.update { it.copy(busy = false, message = "Couldn't reach the inbox with that code. Check your connection and try again.") }
                return@launch
            }
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                store.savePairing(p)
                cachedPairing = p
                pairingChecked = true
                db.clear()
                File(app.filesDir, "staging").deleteRecursively()
                ThumbCache.clearAll(app)
                settings.lastLanHost = null
                settings.pcOnNetwork = false
                // Default: back up photos taken from now on. "Back up older photos" opts into the rest.
                settings.since = System.currentTimeMillis() / 1000
                settings.pairedAt = settings.since
                settings.lastError = null
            }
            Scheduler.schedule(getApplication())
            Scheduler.backUpNow(getApplication())
            _state.update { it.copy(busy = false) }
            refresh()
        }
    }

    fun unpair() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            Scheduler.cancelAll(app)
            store.clear(); settings.clear(); db.clear()
            cachedPairing = null
            pairingChecked = true
            File(app.filesDir, "staging").deleteRecursively()
            ThumbCache.clearAll(app)
            _state.value = UiState(loaded = true)
        }
    }

    fun setDirectEnabled(v: Boolean) { settings.directEnabled = v; refresh() }
    fun setWifiOnly(v: Boolean) { settings.wifiOnly = v; Scheduler.schedule(getApplication()); refresh() }
    fun setBatteryGate(v: Boolean) { settings.batteryGate = v; refresh() }
    fun setBatteryMin(v: Int) { settings.batteryMin = v; refresh() }
    fun setBigMb(v: Int) { settings.bigMb = v; refresh() }
    fun setNewPhotoDelaySecs(v: Int) { settings.newPhotoDelaySecs = v; refresh() }
    fun setFolderHomeOnly(path: String, homeOnly: Boolean) { settings.setFolderHomeOnly(path, homeOnly); refresh() }
    fun setFolderPcDest(path: String, destFolder: String?) { settings.setPcFolder(path, destFolder); refresh() }
    fun setAllFolders(v: Boolean) { settings.allFolders = v; Scheduler.backUpNow(getApplication()); refresh() }

    /** Seed explicit selection from currently active folders on first edit so nothing already active is dropped. */
    private fun currentSelectedSet(): MutableSet<String> {
        if (settings.hasExplicitFolderSelection) return settings.selectedFolders.toMutableSet()
        val seeded = linkedSetOf<String>()
        MediaScanner.discoverDeviceFolders(getApplication(), settings)
            .filter { settings.isFolderSelected(it.relPath) }
            .forEach { seeded += it.relPath }
        db.folderStats()
            .map { FolderSelection.norm(it.path) }
            .filter { it.isNotEmpty() && settings.isFolderSelected(it) }
            .forEach { seeded += it }
        if (seeded.isEmpty() && !settings.allFolders) seeded += "DCIM/Camera"
        return seeded
    }

    fun addFolder(relPath: String) {
        val norm = FolderSelection.norm(relPath)
        if (norm.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val next = currentSelectedSet()
            next += norm
            settings.selectedFolders = next
            Scheduler.backUpNow(getApplication())
            refresh()
        }
    }

    fun removeFolder(relPath: String) {
        val norm = FolderSelection.norm(relPath)
        if (norm.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val next = currentSelectedSet()
            next -= norm
            settings.selectedFolders = next
            settings.removeSafFolder(norm)
            db.removePendingInFolder(norm)
            refresh()
        }
    }

    fun addSafTreeUri(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers don't support persistable grants; still extract path for MediaStore if applicable.
            }
            val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { null }
            val norm = FolderSelection.relativePathFromTreeDocId(docId)
            if (norm == null) {
                message("Pick a specific folder instead of the storage root.")
                return@launch
            }
            settings.addSafFolder(norm, treeUri.toString())
            val next = currentSelectedSet()
            next += norm
            settings.selectedFolders = next
            Scheduler.backUpNow(app)
            refresh()
        }
    }

    fun backUpNow() { Scheduler.backUpNow(getApplication()); message("Backing up…") }

    fun backUpOlder() {
        settings.since = 0
        Scheduler.backUpNow(getApplication())
        message("Older photos will be added to the backup.")
    }

    // ---- safe-to-delete ---------------------------------------------------

    private val pendingUris = ArrayDeque<List<Pair<Long, Uri>>>()

    /**
     * Collects photos the PC has confirmed and that are still exactly what was backed up, in batches of 500
     * (one system prompt each). Returns the first prompt, or null if nothing to delete.
     *
     * A photo edited after it was backed up is never offered: deleting it would lose the edit. It is queued
     * for a fresh backup instead.
     */
    suspend fun startDelete(): IntentSender? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val live = mutableListOf<Pair<Long, Uri>>()
        val gone = mutableListOf<Long>()
        var changed = 0
        for (p in db.byState("synced")) {
            // MediaStore.createTrashRequest only supports MediaStore URIs (SAF-only `.nomedia` files are kept untouched).
            if (p.docUri != null) continue
            when (val now = MediaScanner.currentSize(resolver, p.mediaId, p.isVideo)) {
                null -> gone += p.mediaId
                p.size -> live += (p.mediaId to MediaScanner.uriFor(p.mediaId, p.isVideo))
                else -> { db.requeue(p.mediaId, now, WorkerClient.newObjectId()); changed++ }
            }
        }
        if (gone.isNotEmpty()) {
            db.remove(gone)
            db.markHistoryLocalRemoved(gone)
        }
        if (changed > 0) {
            message("$changed photo(s) changed after backup, so they were kept and will be backed up again.")
            Scheduler.backUpNow(getApplication())
        }
        deleteBatches.clear()
        pendingUris.clear()
        live.chunked(500).forEach { chunk ->
            deleteBatches.addLast(chunk.map { it.first })
            pendingUris.addLast(chunk)
        }
        nextDelete()
    }

    /**
     * Safely removes a single verified History item from the phone via Android's trash prompt,
     * while preserving the History entry and its cached WebP preview thumbnail.
     */
    suspend fun startDeleteSingle(item: HistoryItem): IntentSender? = withContext(Dispatchers.IO) {
        if (!item.onPc || item.localRemoved || item.docUri != null) return@withContext null
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        when (val now = MediaScanner.currentSize(resolver, item.mediaId, item.isVideo)) {
            null -> {
                db.remove(listOf(item.mediaId))
                db.markHistoryLocalRemoved(listOf(item.mediaId))
                refresh()
                message("Already removed from this phone.")
                null
            }
            item.size -> {
                deleteBatches.clear()
                pendingUris.clear()
                val uri = MediaScanner.uriFor(item.mediaId, item.isVideo)
                deleteBatches.addLast(listOf(item.mediaId))
                pendingUris.addLast(listOf(item.mediaId to uri))
                nextDelete()
            }
            else -> {
                db.requeue(item.mediaId, now, WorkerClient.newObjectId())
                Scheduler.backUpNow(app)
                refresh()
                message("This item was edited after backup, so it was kept and queued again.")
                null
            }
        }
    }

    fun nextDelete(): IntentSender? {
        val batch = deleteBatches.removeFirstOrNull() ?: return null
        val pairs = pendingUris.removeFirstOrNull() ?: return null
        activeBatch = batch
        val uris = pairs.map { it.second }
        // Trash, not permanent delete: the phone keeps them restorable for about 30 days.
        return MediaStore.createTrashRequest(getApplication<Application>().contentResolver, uris, true).intentSender
    }

    /** The user answered the system prompt for the active batch. */
    fun deleteResult(confirmed: Boolean): IntentSender? {
        val batch = activeBatch
        activeBatch = null
        if (confirmed && batch != null) {
            db.remove(batch)
            db.markHistoryLocalRemoved(batch)
            refresh()
            return nextDelete()
        }
        deleteBatches.clear()
        pendingUris.clear()
        refresh()
        return null
    }
}
