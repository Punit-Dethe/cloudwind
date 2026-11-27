package dev.cloudwind.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.cloudwind.core.Crypto
import dev.cloudwind.core.HttpError
import dev.cloudwind.core.WorkerClient
import java.io.File
import java.io.IOException

/** One backup pass: find new media, encrypt + upload what's pending, then pull status back. */
class SyncRunner(private val context: Context) {
    private val settings = Settings(context)
    private val db = PhotoDb(context)
    private val store = SecureStore(context)

    sealed interface Outcome {
        data object NotPaired : Outcome
        data class Done(val uploaded: Int) : Outcome
        /** Network trouble or server hiccup; WorkManager should retry later. */
        data class Retry(val message: String) : Outcome
        /** Bad token or key: retrying won't help until the user re-pairs. */
        data class AuthFailed(val message: String) : Outcome
    }

    fun pendingCount() = db.counts().pending

    /** `onProgress(done, total, name)` is called before each file. */
    fun run(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }, isStopped: () -> Boolean = { false }): Outcome {
        val pairing = store.loadPairing() ?: return Outcome.NotPaired
        val client = WorkerClient(pairing.url, pairing.token)
        val resolver = context.contentResolver

        MediaScanner.scan(resolver, db, settings)

        val pending = db.byState("pending")
        var uploaded = 0
        for ((i, p) in pending.withIndex()) {
            if (isStopped()) return Outcome.Retry("stopped")
            onProgress(i, pending.size, p.name)
            try {
                uploadOne(client, pairing.key, p)
                db.markUploaded(p.mediaId, System.currentTimeMillis() / 1000)
                uploaded++
            } catch (e: HttpError) {
                if (e.code == 401 || e.code == 403) return fail(Outcome.AuthFailed("The Cloudwind inbox rejected this phone's token. Pair again."))
                db.setError(p.mediaId, e.message ?: "upload failed")
                if (e.code >= 500 || e.code == 429) return fail(Outcome.Retry(e.message ?: "server error"))
            } catch (e: SecurityException) {
                return fail(Outcome.AuthFailed("Photo access was revoked. Grant it again in Settings."))
            } catch (e: IOException) {
                // A missing file (deleted since the scan) is skipped; network errors stop the pass.
                if (!MediaScanner.exists(resolver, p.mediaId, p.isVideo)) { db.remove(listOf(p.mediaId)); continue }
                return fail(Outcome.Retry(e.message ?: "network error"))
            }
        }

        try {
            refreshStatus(client)
        } catch (e: HttpError) {
            if (e.code == 401 || e.code == 403) return fail(Outcome.AuthFailed("The Cloudwind inbox rejected this phone's token. Pair again."))
            return fail(Outcome.Retry(e.message ?: "status failed"))
        } catch (e: IOException) {
            return fail(Outcome.Retry(e.message ?: "network error"))
        }
        settings.lastError = null
        settings.lastRunAt = System.currentTimeMillis() / 1000
        return Outcome.Done(uploaded)
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
