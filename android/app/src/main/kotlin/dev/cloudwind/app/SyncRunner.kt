package dev.cloudwind.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.cloudwind.core.Crypto
import dev.cloudwind.core.HttpError
import dev.cloudwind.core.WorkerClient
import java.io.File
import java.io.IOException

/**
 * One backup pass: find new media, encrypt + upload what's pending (newest first), then pull
 * status back. It is built to keep making progress around problems:
 *  - inbox full: skip items that don't fit, upload smaller ones, report "waiting for PC"
 *  - file over the per-file limit: set aside permanently, never retried
 *  - phone too low on space to stage a big encrypted copy: skip it for now
 *  - file still being written: skip it for now
 *  - rate limited / server trouble / no network: stop and let WorkManager retry with backoff
 */
class SyncRunner(private val context: Context) {
    private val settings = Settings(context)
    private val db = PhotoDb(context)
    private val store = SecureStore(context)

    sealed interface Outcome {
        data object NotPaired : Outcome
        data class Done(val uploaded: Int) : Outcome
        /** Network trouble, rate limit or server hiccup; WorkManager should retry later. */
        data class Retry(val message: String) : Outcome
        /** Bad token or key: retrying won't help until the user re-pairs. */
        data class AuthFailed(val message: String) : Outcome
    }

    fun pendingCount() = db.counts().pending

    /** Encrypted size is the plaintext plus a 16-byte tag per 1 MiB chunk plus small headers. */
    private fun encryptedSize(size: Long) = size + (size / (1 shl 20) + 1) * 16 + 1024

    /** `onProgress(done, total, name)` is called before each file. */
    fun run(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }, isStopped: () -> Boolean = { false }): Outcome {
        val pairing = store.loadPairing() ?: return Outcome.NotPaired
        val client = WorkerClient(pairing.url, pairing.token)
        val resolver = context.contentResolver

        MediaScanner.scan(resolver, db, settings)

        // Learn the server's limits up front so we don't encrypt files that can't be accepted.
        val limits = try { client.limits() } catch (e: HttpError) { return httpFailure(e) } catch (e: IOException) { return fail(Outcome.Retry(e.message ?: "network error")) }
        settings.maxObjectBytes = limits.max_object_bytes
        var free = limits.free_bytes
        var inboxFull = false
        var notice: String? = null

        val pending = db.byState("pending", newestFirst = true)
        var uploaded = 0
        for ((i, p) in pending.withIndex()) {
            if (isStopped()) return Outcome.Retry("stopped")
            val need = encryptedSize(p.size)
            if (need > limits.max_object_bytes) { db.markSkipped(p.mediaId, "too_large"); continue }
            if (need > free) { inboxFull = true; continue } // a smaller item later in the list may still fit
            if (context.cacheDir.usableSpace < need + (64L shl 20)) {
                db.setError(p.mediaId, "low_phone_space")
                notice = "Not enough free space on this phone to prepare “${p.name}” for upload. Free some space and it will continue."
                continue
            }
            onProgress(i, pending.size, p.name)
            try {
                uploadOne(client, pairing.key, p)
                db.markUploaded(p.mediaId, System.currentTimeMillis() / 1000)
                uploaded++
                free -= need
            } catch (e: HttpError) {
                when {
                    e.isInboxFull -> { free = e.freeBytes ?: 0; inboxFull = true }
                    e.isTooLarge -> db.markSkipped(p.mediaId, "too_large")
                    e.code == 401 || e.code == 403 || e.code == 429 || e.code >= 500 -> return httpFailure(e)
                    else -> db.setError(p.mediaId, e.message ?: "upload failed")
                }
            } catch (e: SecurityException) {
                return fail(Outcome.AuthFailed("Photo access was revoked. Grant it again in Settings."))
            } catch (e: Crypto.SourceChangedException) {
                db.setError(p.mediaId, "still being written") // e.g. a video that is still recording; next pass
            } catch (e: IOException) {
                // A missing file (deleted since the scan) is dropped; network errors stop the pass.
                if (!MediaScanner.exists(resolver, p.mediaId, p.isVideo)) { db.remove(listOf(p.mediaId)); continue }
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

    private fun uploadOne(client: WorkerClient, key: ByteArray, p: Photo) {
        val resolver = context.contentResolver
        val uri = MediaScanner.uriFor(p.mediaId, p.isVideo)
        // Ask for the unredacted original (keeps GPS EXIF); fall back if the permission is missing.
        val source: Uri = try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri }
        val open = {
            try { resolver.openInputStream(source) } catch (e: UnsupportedOperationException) { resolver.openInputStream(uri) }
                ?: throw IOException("cannot open ${p.name}")
        }
        val tmp = File.createTempFile("up", ".enc", context.cacheDir)
        try {
            tmp.outputStream().buffered().use { out -> Crypto.encrypt(key, p.name, null, open, out) }
            client.upload(p.objectId, tmp)
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
