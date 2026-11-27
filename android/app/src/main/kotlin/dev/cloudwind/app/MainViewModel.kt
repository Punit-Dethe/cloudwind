package dev.cloudwind.app

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cloudwind.core.Pairing
import dev.cloudwind.core.WorkerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val paired: Boolean = false,
    val server: String = "",
    val counts: Counts = Counts(),
    val inboxBytes: Long = 0,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val allFolders: Boolean = false,
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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Batches waiting for the user's system delete confirmation. */
    private val deleteBatches = ArrayDeque<List<Photo>>()
    private var activeBatch: List<Photo>? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val pairing = store.loadPairing()
            val counts = db.counts()
            _state.update {
                it.copy(
                    paired = pairing != null, server = pairing?.url ?: "", counts = counts,
                    inboxBytes = SyncRunner.lastInboxBytes,
                    wifiOnly = settings.wifiOnly, chargingOnly = settings.chargingOnly, allFolders = settings.allFolders,
                    lastError = settings.lastError,
                    inboxFull = settings.inboxFull, notice = settings.notice, maxObjectBytes = settings.maxObjectBytes,
                    access = MediaAccess.current(getApplication()),
                    lastRunAt = settings.lastRunAt, pairedAt = settings.pairedAt,
                    nowSecs = System.currentTimeMillis() / 1000,
                    batteryUnrestricted = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(getApplication<Application>().packageName),
                )
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
                store.savePairing(p)
                db.clear()
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
            Scheduler.cancelAll(getApplication())
            store.clear(); settings.clear(); db.clear()
            _state.value = UiState()
        }
    }

    fun setWifiOnly(v: Boolean) { settings.wifiOnly = v; Scheduler.schedule(getApplication()); refresh() }
    fun setChargingOnly(v: Boolean) { settings.chargingOnly = v; Scheduler.schedule(getApplication()); refresh() }
    fun setAllFolders(v: Boolean) { settings.allFolders = v; Scheduler.backUpNow(getApplication()); refresh() }

    fun backUpNow() { Scheduler.backUpNow(getApplication()); message("Backing up…") }

    fun backUpOlder() {
        settings.since = 0
        Scheduler.backUpNow(getApplication())
        message("Older photos will be added to the backup.")
    }

    // ---- safe-to-delete ---------------------------------------------------

    /**
     * Collects photos the PC has confirmed and that still exist on the phone, in batches of
     * 500 (one system prompt each). Returns the first prompt, or null if nothing to delete.
     */
    suspend fun startDelete(): IntentSender? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val live = mutableListOf<Photo>()
        val gone = mutableListOf<Long>()
        for (p in db.byState("synced")) {
            if (MediaScanner.exists(resolver, p.mediaId, p.isVideo)) live += p else gone += p.mediaId
        }
        if (gone.isNotEmpty()) db.remove(gone) // already deleted elsewhere
        deleteBatches.clear()
        live.chunked(500).forEach { deleteBatches.addLast(it) }
        nextDelete()
    }

    fun nextDelete(): IntentSender? {
        val batch = deleteBatches.removeFirstOrNull() ?: return null
        activeBatch = batch
        val uris = batch.map { MediaScanner.uriFor(it.mediaId, it.isVideo) }
        return MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris).intentSender
    }

    /** The user answered the system prompt for the active batch. */
    fun deleteResult(confirmed: Boolean): IntentSender? {
        val batch = activeBatch
        activeBatch = null
        if (confirmed && batch != null) {
            db.remove(batch.map { it.mediaId })
            refresh()
            return nextDelete()
        }
        deleteBatches.clear()
        refresh()
        return null
    }
}
