package dev.cloudwind.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import dev.cloudwind.core.Crypto
import dev.cloudwind.core.HttpError
import dev.cloudwind.core.ResumeState
import dev.cloudwind.core.UploadInterrupted
import dev.cloudwind.core.WorkerClient
import java.io.File
import java.io.IOException

/**
 * One backup pass: find new media, encrypt + upload what's pending (newest first), then pull
 * status back. It is built to keep making progress around problems:
 *  - big files: the encrypted copy is kept on disk and multipart progress is saved after every
 *    part, so an interrupted upload (OS stop, network drop, app update) continues where it left off
 *  - time budget: a pass stops cleanly at its deadline and the worker schedules a continuation,
 *    instead of being killed mid-part
 *  - inbox full: skip items that don't fit, upload smaller ones, report "waiting for PC"
 *  - file over the per-file limit: set aside permanently, never retried
 *  - phone too low on space to stage a big encrypted copy: skip it for now
 *  - file still being written: skip it for now
 *  - only some photos shared (Android 14+): warn instead of looking healthy
 *  - rate limited / server trouble / no network: stop and let WorkManager retry with backoff
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

    /**
     * `budgetMs` is how long this pass may run. `onProgress(done, total, name)` is called before each file.
     */
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

        // Learn the server's limits up front so we don't encrypt files that can't be accepted.
        val limits = try { client.limits() } catch (e: HttpError) { return httpFailure(e) } catch (e: IOException) { return fail(Outcome.Retry(e.message ?: "network error")) }
        settings.maxObjectBytes = limits.max_object_bytes
        var free = limits.free_bytes
        var inboxFull = false
        var notice: String? = if (access == MediaAccess.PARTIAL) "Cloudwind can only see the photos you chose to share. Allow all photos so new ones back up automatically." else null

        val pending = db.byState("pending", newestFirst = true)
        var uploaded = 0
        for ((i, p) in pending.withIndex()) {
            if (shouldStop()) return paused(isStopped, uploaded)
            val resuming = db.stageSize(p.mediaId) != null
            val need = encryptedSize(p.size)
            if (need > limits.max_object_bytes) { db.markSkipped(p.mediaId, "too_large"); continue }
            if (need > free) { inboxFull = true; continue } // a smaller item later in the list may still fit
            if (!resuming && stagingDir.usableSpace < need + (64L shl 20)) {
                db.setError(p.mediaId, "low_phone_space")
                notice = "Not enough free space on this phone to prepare “${p.name}” for upload. Free some space and it will continue."
                continue
            }
            onProgress(i, pending.size, p.name)
            try {
                uploadOne(client, pairing.key, p, shouldStop)
                db.markUploaded(p.mediaId, System.currentTimeMillis() / 1000)
                uploaded++
                free -= need
            } catch (e: UploadInterrupted) {
                return paused(isStopped, uploaded) // progress is already saved
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
                db.setError(p.mediaId, "still being written") // e.g. a video that is still recording; next pass
            } catch (e: IOException) {
                // A missing file (deleted since the scan) is dropped; network errors stop the pass.
                if (!MediaScanner.exists(resolver, p.mediaId, p.isVideo)) { dropStage(p); db.remove(listOf(p.mediaId)); continue }
                return fail(Outcome.Retry(e.message ?: "network error"))
            }
        }

        try {
            refreshStatus(client)
        } catch (e: HttpError) {
            return httpFailure(e)
        } catch (e: IOException) {
            return fail(Outcome.Retry(e.message ?: "network error"))
        }
        settings.inboxFull = inboxFull
        settings.notice = notice
        settings.lastError = null
        settings.lastRunAt = System.currentTimeMillis() / 1000
        return Outcome.Done(uploaded)
    }

    private fun paused(isStopped: () -> Boolean, uploaded: Int): Outcome {
        val outcome = Outcome.Paused(deadlineHit = !isStopped(), uploaded = uploaded)
        // Pausing with progress counts as a healthy pass for the "backups stopped" warning.
        if (uploaded > 0) settings.lastRunAt = System.currentTimeMillis() / 1000
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
        val resolver = context.contentResolver
        val uri = MediaScanner.uriFor(p.mediaId, p.isVideo)
        // Ask for the unredacted original (keeps GPS EXIF); fall back if the permission is missing.
        val source: Uri = try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri }
        val open = {
            try { resolver.openInputStream(source) } catch (e: UnsupportedOperationException) { resolver.openInputStream(uri) }
                ?: throw IOException("cannot open ${p.name}")
        }
        val tmp = File(stagingDir, "${p.objectId}.tmp")
        try {
            tmp.outputStream().buffered().use { out -> Crypto.encrypt(key, p.name, null, open, out) }
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
    }
}
