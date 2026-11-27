package dev.cloudwind.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import dev.cloudwind.core.Conditions
import dev.cloudwind.core.Crypto
import dev.cloudwind.core.Decision
import dev.cloudwind.core.FileInfo
import dev.cloudwind.core.HttpError
import dev.cloudwind.core.LanClient
import dev.cloudwind.core.LanFinder
import dev.cloudwind.core.LanHint
import dev.cloudwind.core.LanPlanItem
import dev.cloudwind.core.LanSend
import dev.cloudwind.core.LanUnavailable
import dev.cloudwind.core.Limits
import dev.cloudwind.core.Pairing
import dev.cloudwind.core.Policy
import dev.cloudwind.core.ResumeState
import dev.cloudwind.core.RoutePlanner
import dev.cloudwind.core.UploadInterrupted
import dev.cloudwind.core.Wait
import dev.cloudwind.core.WorkerClient
import java.io.File
import java.io.IOException

/**
 * One backup pass. For every file there is exactly one route at a time:
 *
 *  1. Look for the PC on the current network (last address, then the encrypted hint from the cloud).
 *  2. If found, send it a plan. The PC answers which files it already has, which it will pull from the
 *     cloud itself, and which it wants from us (with the byte to resume from). Wanted files go straight over.
 *  3. Whatever is still on the phone is routed by [RoutePlanner]: cloud if the gates allow, otherwise it
 *     waits (and we say why). Home-only folders never take this step.
 *  4. Pull "synced" confirmations from the cloud.
 *
 * Around that it keeps making progress through trouble: uploads resume from saved parts, a pass stops
 * cleanly at its time budget, a dropped direct send falls back to the cloud (unless Home-only), and so on.
 */
class SyncRunner(private val context: Context) {
    private val settings = Settings(context)
    private val db = PhotoDb(context)
    private val store = SecureStore(context)
    private val stagingDir = File(context.filesDir, "staging").apply { mkdirs() }

    sealed interface Outcome {
        data object NotPaired : Outcome
        data class Done(val uploaded: Int) : Outcome
        /** Ran out of time (or was stopped) with work left; progress is saved. */
        data class Paused(val deadlineHit: Boolean, val uploaded: Int) : Outcome
        /** Network trouble, rate limit or server hiccup; WorkManager should retry later. */
        data class Retry(val message: String) : Outcome
        /** Bad token, key or revoked access: retrying won't help until the user fixes it. */
        data class AuthFailed(val message: String) : Outcome
    }

    fun pendingCount() = db.counts().pending

    /** Encrypted size is the plaintext plus a 16-byte tag per 1 MiB chunk plus small headers. */
    private fun encryptedSize(size: Long) = size + (size / (1 shl 20) + 1) * 16 + 1024

    private class Pass(var direct: Int = 0, var cloud: Int = 0, val waits: MutableMap<Wait, Int> = mutableMapOf())

    fun run(
        budgetMs: Long = 8 * 60_000L,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
        isStopped: () -> Boolean = { false },
    ): Outcome {
        val pairing = store.loadPairing() ?: return Outcome.NotPaired
        val client = WorkerClient(pairing.url, pairing.token)
        val resolver = context.contentResolver
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        val shouldStop = { isStopped() || SystemClock.elapsedRealtime() > deadline }

        val access = MediaAccess.current(context)
        if (access == MediaAccess.NONE) {
            return fail(Outcome.AuthFailed("Photo access was removed. Allow it again to continue backing up."))
        }

        cleanStaging()
        MediaScanner.scan(resolver, db, settings)

        val gates = settings.gates
        val pass = Pass()
        var notice: String? = if (access == MediaAccess.PARTIAL) "Cloudwind can only see the photos you chose to share. Allow all photos so new ones back up automatically." else null

        // ---- 1. is the PC on this network? -------------------------------------------------
        var internet = Device.conditions(context, false).internet
        var lan: LanClient? = if (Device.onLocalNetwork(context)) findPc(pairing, client, internet) else null
        settings.pcOnNetwork = lan != null

        // ---- 2. direct phase -----------------------------------------------------------------
        if (lan != null) {
            try {
                directPhase(lan, client, pairing, pass, gates, shouldStop)
            } catch (e: UploadInterrupted) {
                return paused(isStopped, pass)
            } catch (e: LanUnavailable) {
                lan = null // PC went away mid-pass: the rest falls back to the cloud (or waits if Home-only)
                settings.pcOnNetwork = false
            }
        }

        // ---- 3. cloud phase --------------------------------------------------------------------
        internet = Device.conditions(context, false).internet
        var limits: Limits? = null
        if (internet) {
            limits = try { client.limits() } catch (e: HttpError) { return httpFailure(e) } catch (e: IOException) { null }
            limits?.let { settings.maxObjectBytes = it.max_object_bytes }
        }
        var free = limits?.free_bytes ?: Long.MAX_VALUE
        var inboxFull = false

        val pending = db.byState("pending", newestFirst = true)
        for ((i, p) in pending.withIndex()) {
            if (shouldStop()) return paused(isStopped, pass)
            val cond = Device.conditions(context, lanReachable = lan != null)
            val need = encryptedSize(p.size)
            val decision = RoutePlanner.decide(FileInfo(p.size, settings.policyFor(p.relPath)), cond, gates, limits?.copy(free_bytes = free))
            when (decision) {
                is Decision.Wait -> { pass.waits.merge(decision.why, 1, Int::plus); if (decision.why == Wait.INBOX_FULL) inboxFull = true; continue }
                Decision.Lan, Decision.InCloud -> continue // handled by the direct phase / the PC pulls it
                Decision.Cloud -> {}
            }
            val resuming = db.stageSize(p.mediaId) != null
            if (!resuming && stagingDir.usableSpace < need + (64L shl 20)) {
                db.setError(p.mediaId, "low_phone_space")
                notice = "Not enough free space on this phone to prepare “${p.name}” for upload. Free some space and it will continue."
                continue
            }
            onProgress(i, pending.size, p.name)
            try {
                uploadOne(client, pairing.key, p, shouldStop)
                db.markUploaded(p.mediaId, System.currentTimeMillis() / 1000)
                pass.cloud++
                free -= need
            } catch (e: UploadInterrupted) {
                return paused(isStopped, pass)
            } catch (e: HttpError) {
                when {
                    e.isInboxFull -> { free = e.freeBytes ?: 0; inboxFull = true }
                    e.isTooLarge -> { dropStage(p); db.markSkipped(p.mediaId, "too_large") }
                    e.code == 401 || e.code == 403 || e.code == 429 || e.code >= 500 -> return httpFailure(e)
                    else -> db.setError(p.mediaId, e.message ?: "upload failed")
                }
            } catch (e: SecurityException) {
                return fail(Outcome.AuthFailed("Photo access was revoked. Grant it again in Settings."))
            } catch (e: Crypto.SourceChangedException) {
                db.setError(p.mediaId, "still being written")
            } catch (e: IOException) {
                if (!MediaScanner.exists(resolver, p.mediaId, p.isVideo)) { dropStage(p); db.remove(listOf(p.mediaId)); continue }
                return fail(Outcome.Retry(e.message ?: "network error"))
            }
        }

        // ---- 4. confirmations from the cloud -----------------------------------------------
        if (internet) {
            try {
                refreshStatus(client)
            } catch (e: HttpError) {
                return httpFailure(e)
            } catch (e: IOException) {
                return fail(Outcome.Retry(e.message ?: "network error"))
            }
        }
        finish(pass, inboxFull, notice)
        return Outcome.Done(pass.direct + pass.cloud)
    }

    // ============================== direct route ==============================

    /** Find the PC: last address that worked, then the encrypted address hint the PC publishes to the cloud. */
    private fun findPc(pairing: Pairing, client: WorkerClient, internet: Boolean): LanClient? {
        val fp = pairing.lanFingerprint ?: return null
        val token = dev.cloudwind.core.lanAuthToken(pairing.key)
        val candidates = mutableListOf<String>()
        settings.lastLanHost?.let { candidates += it }
        var hintPort: Int? = null
        if (internet) {
            try {
                client.lanHint()?.let { LanHint.decode(it, pairing.key) }?.let { h -> candidates += h.addrs; hintPort = h.port.takeIf { it > 0 } }
            } catch (e: IOException) { /* the hint is optional */ }
        }
        val port = hintPort ?: pairing.lanPort ?: return null
        for (host in candidates.distinct()) {
            val c = LanClient(host, port, fp, token)
            if (c.hello()) { settings.lastLanHost = host; return c }
        }
        return null
    }

    private fun ensureHash(p: Photo, shouldStop: () -> Boolean): Pair<String, Long>? {
        db.cachedHash(p.mediaId, p.size)?.let { return it to p.size }
        if (shouldStop()) return null
        return try {
            val (sha, n) = openOriginal(p).use { Crypto.hash(it) }
            db.setHash(p.mediaId, sha, n)
            sha to n
        } catch (e: IOException) { null }
    }

    private fun openOriginal(p: Photo): java.io.InputStream {
        val resolver = context.contentResolver
        val uri = MediaScanner.uriFor(p.mediaId, p.isVideo)
        val source: Uri = try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri }
        return (try { resolver.openInputStream(source) } catch (e: UnsupportedOperationException) { resolver.openInputStream(uri) })
            ?: throw IOException("cannot open ${p.name}")
    }

    private fun directPhase(lan: LanClient, client: WorkerClient, pairing: Pairing, pass: Pass, gates: dev.cloudwind.core.Gates, shouldStop: () -> Boolean) {
        val rows = db.unsynced()
        if (rows.isEmpty()) return

        // A big cloud upload that is nearly done is finished by the cloud; a long one is cancelled and goes direct.
        val candidates = ArrayList<Photo>()
        for (p in rows) {
            val resume = if (p.state == "pending") ResumeState.fromJson(db.resumeJson(p.mediaId)) else null
            if (resume != null) {
                val sent = resume.parts.size * WorkerClient.PART_SIZE
                val remaining = (db.stageSize(p.mediaId) ?: p.size) - sent
                if (RoutePlanner.finishCloudInstead(remaining)) continue
                try { client.abortUpload(p.objectId, resume.uploadId) } catch (e: IOException) { /* reservation expires on its own */ }
                dropStage(p)
            }
            candidates += p
        }

        // Hash what we can this pass (cached after the first time); the rest is planned next pass.
        val hashed = LinkedHashMap<String, Triple<Photo, String, Long>>() // id -> (row, sha, size)
        for (p in candidates) {
            val (sha, n) = ensureHash(p, shouldStop) ?: continue
            hashed[p.mediaId.toString()] = Triple(p, sha, n)
        }
        if (hashed.isEmpty()) return

        val plan = lan.plan(hashed.map { (id, t) -> LanPlanItem(id, t.second, t.third, t.first.objectId.takeIf { t.first.state == "uploaded" }) })
        val now = System.currentTimeMillis() / 1000
        plan.have.forEach { id -> hashed[id]?.let { db.markSyncedDirect(it.first.mediaId, now); pass.direct++ } }

        val cond = Device.conditions(context, lanReachable = true)
        for ((i, w) in plan.want.withIndex()) {
            if (shouldStop()) throw UploadInterrupted()
            val (p, sha, n) = hashed[w.id] ?: continue
            if (!RoutePlanner.batteryOk(FileInfo(n, settings.policyFor(p.relPath), p.state == "uploaded"), cond, gates)) {
                pass.waits.merge(Wait.BATTERY, 1, Int::plus)
                continue
            }
            if (sendDirect(lan, p, sha, n, w.offset, shouldStop)) {
                db.markSyncedDirect(p.mediaId, System.currentTimeMillis() / 1000)
                pass.direct++
            }
        }
    }

    /**
     * Send one file, resuming from the byte the PC holds. Wi-Fi blips are common, so a few quick retries come
     * first; if the PC is really gone this throws [LanUnavailable] and the caller falls back to the cloud.
     */
    private fun sendDirect(lan: LanClient, p: Photo, sha: String, size: Long, startOffset: Long, shouldStop: () -> Boolean): Boolean {
        var offset = startOffset
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                when (val r = lan.send(sha, size, offset, p.name, null, p.objectId.takeIf { p.state == "uploaded" }, { openOriginal(p) }, shouldStop)) {
                    is LanSend.Stored -> return true
                    is LanSend.Conflict -> offset = r.have
                    is LanSend.Progress -> offset = r.have
                    LanSend.BadHash -> { db.clearHash(p.mediaId); db.setError(p.mediaId, "changed while sending"); return false }
                    LanSend.Busy -> Thread.sleep(2000)
                }
            } catch (e: UploadInterrupted) {
                throw e
            } catch (e: LanUnavailable) {
                throw e
            } catch (e: IOException) {
                last = e
                Thread.sleep(1500L * (attempt + 1))
                // Ask the PC how much it has, since our idea of the offset may be stale.
                offset = try { lan.plan(listOf(LanPlanItem("x", sha, size))).want.firstOrNull()?.offset ?: return true } catch (e2: IOException) { throw LanUnavailable("PC unreachable", e2) }
            }
        }
        throw LanUnavailable("direct send kept failing", last)
    }

    // ============================== cloud route ==============================

    private fun paused(isStopped: () -> Boolean, pass: Pass): Outcome {
        val outcome = Outcome.Paused(deadlineHit = !isStopped(), uploaded = pass.direct + pass.cloud)
        // Pausing with progress counts as a healthy pass for the "backups stopped" warning.
        if (pass.direct + pass.cloud > 0) settings.lastRunAt = System.currentTimeMillis() / 1000
        return outcome
    }

    private fun httpFailure(e: HttpError): Outcome = when {
        e.code == 401 || e.code == 403 -> fail(Outcome.AuthFailed("The Cloudwind inbox rejected this phone's token. Pair again."))
        e.isRateLimited -> fail(Outcome.Retry("Too many requests; pausing for a bit."))
        else -> fail(Outcome.Retry(e.message ?: "server error"))
    }

    private fun fail(o: Outcome): Outcome {
        settings.lastError = when (o) {
            is Outcome.Retry -> o.message
            is Outcome.AuthFailed -> o.message
            else -> null
        }
        return o
    }

    private fun finish(pass: Pass, inboxFull: Boolean, notice: String?) {
        settings.inboxFull = inboxFull
        settings.notice = notice
        settings.lastDirect = pass.direct
        settings.lastCloud = pass.cloud
        settings.waiting = summarizeWaits(pass.waits)
        settings.lastError = null
        settings.lastRunAt = System.currentTimeMillis() / 1000
    }

    private fun stagedFile(p: Photo) = File(stagingDir, "${p.objectId}.enc")

    private fun dropStage(p: Photo) {
        stagedFile(p).delete()
        db.clearStage(p.mediaId)
    }

    /** Remove staged files nothing refers to (finished, deleted, or left by a crash mid-encrypt). */
    private fun cleanStaging() {
        val keep = db.stagedObjectIds()
        stagingDir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.')
            if (!f.name.endsWith(".enc") || id !in keep) f.delete()
        }
    }

    private fun uploadOne(client: WorkerClient, key: ByteArray, p: Photo, shouldStop: () -> Boolean) {
        val staged = stagedFile(p)
        val recorded = db.stageSize(p.mediaId)
        val reusable = recorded != null && staged.exists() && staged.length() == recorded
        if (!reusable) {
            dropStage(p)
            stage(key, p, staged)
        }
        client.upload(
            p.objectId, staged,
            resume = ResumeState.fromJson(db.resumeJson(p.mediaId)),
            onState = { db.setResume(p.mediaId, it?.toJson()) },
            shouldStop = shouldStop,
        )
        dropStage(p)
    }

    /** Encrypt to a temp name, then rename, so a half-written file is never mistaken for a staged one. */
    private fun stage(key: ByteArray, p: Photo, dest: File) {
        val tmp = File(stagingDir, "${p.objectId}.tmp")
        try {
            tmp.outputStream().buffered().use { out -> Crypto.encrypt(key, p.name, null, { openOriginal(p) }, out) }
            if (!tmp.renameTo(dest)) throw IOException("could not stage ${p.name}")
            db.setStage(p.mediaId, dest.length())
        } finally {
            tmp.delete()
        }
    }

    /** Pull "synced" confirmations from the Worker and update local rows. */
    private fun refreshStatus(client: WorkerClient) {
        val since = db.oldestUploadedAt() ?: return
        var cursor = since
        repeat(50) {
            val r = client.status(cursor)
            r.objects.forEach { o -> if (o.state == "synced") db.markSynced(o.id, o.synced_at ?: (System.currentTimeMillis() / 1000)) }
            lastInboxBytes = r.inbox_bytes
            if (r.objects.size < 1000) return
            cursor = r.objects.maxOf { it.uploaded_at }
        }
    }

    companion object {
        /** Bytes currently held in the cloud inbox, as last reported by the Worker. */
        @Volatile var lastInboxBytes: Long = 0

        /** e.g. "3 waiting for Wi-Fi · 2 waiting for your home network". Null when nothing is held back. */
        fun summarizeWaits(waits: Map<Wait, Int>): String? {
            if (waits.isEmpty()) return null
            val label = mapOf(
                Wait.WIFI to "waiting for Wi-Fi", Wait.BATTERY to "waiting for charge or battery",
                Wait.HOME_NETWORK to "waiting for your home network", Wait.NETWORK to "waiting for a connection",
                Wait.INBOX_FULL to "waiting for room in the cloud inbox",
            )
            val order = listOf(Wait.WIFI, Wait.BATTERY, Wait.HOME_NETWORK, Wait.NETWORK, Wait.INBOX_FULL)
            return order.filter { (waits[it] ?: 0) > 0 }.joinToString(" · ") { "${waits[it]} ${label[it]}" }
        }
    }
}
