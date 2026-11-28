package dev.cloudwind.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.FileOutputStream

/**
 * Compact 320px lossy-WebP preview thumbnail cache (`filesDir/thumbs/`).
 *
 * Previews are ~6-10 KB each and capped at [HistoryLogic.MAX_THUMBS] (500 items ≈ 3-5 MB max),
 * allowing the History photo grid to stay rich and instant even after local phone copies are freed.
 */
object ThumbCache {
    private const val MAX_DIM = 320
    private const val WEBP_QUALITY = 65

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "thumbs").apply { mkdirs() }

    fun fileFor(context: Context, thumbFile: String?): File? {
        if (thumbFile.isNullOrBlank()) return null
        val f = File(dir(context), thumbFile)
        return if (f.exists() && f.length() > 0L) f else null
    }

    /**
     * Generates and caches a compact WebP thumbnail for [p] if one doesn't already exist.
     * Never throws — thumbnail failure must never interrupt a real backup.
     */
    fun ensureThumbnail(context: Context, p: Photo): String? =
        ensureThumbnail(context, p.objectId, p.mediaId, p.isVideo, p.docUri)

    fun ensureThumbnail(
        context: Context,
        objectId: String,
        mediaId: Long,
        isVideo: Boolean,
        docUri: String?,
    ): String? {
        val safeName = sanitizeObjectId(objectId)
        val fileName = "$safeName.webp"
        val thumbsDir = dir(context)
        val target = File(thumbsDir, fileName)
        if (target.exists() && target.length() > 0L) return fileName

        val raw = loadRawBitmap(context.contentResolver, mediaId, isVideo, docUri) ?: return null
        val scaled = scaleDown(raw, MAX_DIM)
        val tmp = File(thumbsDir, "$safeName.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
            }
            if (tmp.length() > 0L && tmp.renameTo(target)) fileName else null
        } catch (_: Exception) {
            null
        } finally {
            if (scaled !== raw) scaled.recycle()
            raw.recycle()
            tmp.delete()
        }
    }

    /** Queries video duration in milliseconds for MediaStore videos; returns 0 for images or SAF items. */
    fun queryDurationMs(resolver: ContentResolver, mediaId: Long, isVideo: Boolean, docUri: String?): Long {
        if (!isVideo || docUri != null) return 0L
        return try {
            resolver.query(
                MediaScanner.uriFor(mediaId, isVideo = true),
                arrayOf(MediaStore.Video.Media.DURATION),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).coerceAtLeast(0L) else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** Enforces the rolling [HistoryLogic.MAX_THUMBS] cap and cleans up any unreferenced/temp files. */
    fun prune(context: Context, db: PhotoDb) {
        try {
            val ordered = db.thumbFilesNewestFirst()
            val toDrop = HistoryLogic.thumbsToPrune(ordered, HistoryLogic.MAX_THUMBS)
            val thumbsDir = dir(context)
            if (toDrop.isNotEmpty()) {
                toDrop.forEach { File(thumbsDir, it).delete() }
                db.clearThumbFiles(toDrop)
            }
            val keep = ordered.take(HistoryLogic.MAX_THUMBS).toHashSet()
            thumbsDir.listFiles()?.forEach { f ->
                if (!f.name.endsWith(".webp") || f.name !in keep) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
            // Non-fatal housekeeping.
        }
    }

    /** Wipes every cached preview thumbnail (called on unpair / fresh pair). */
    fun clearAll(context: Context) {
        try {
            File(context.applicationContext.filesDir, "thumbs").deleteRecursively()
        } catch (_: Exception) {}
    }

    private fun loadRawBitmap(
        resolver: ContentResolver,
        mediaId: Long,
        isVideo: Boolean,
        docUri: String?,
    ): Bitmap? {
        if (docUri != null) {
            val uri = Uri.parse(docUri)
            val thumb = try {
                DocumentsContract.getDocumentThumbnail(resolver, uri, Point(MAX_DIM, MAX_DIM), null)
            } catch (_: Exception) {
                null
            }
            if (thumb != null) return thumb
            if (!isVideo) return decodeSampled(resolver, uri)
            return null
        }

        val uri = MediaScanner.uriFor(mediaId, isVideo)
        val nativeThumb = try {
            resolver.loadThumbnail(uri, Size(MAX_DIM, MAX_DIM), null)
        } catch (_: Exception) {
            null
        }
        if (nativeThumb != null) return nativeThumb
        if (!isVideo) return decodeSampled(resolver, uri)
        return null
    }

    private fun decodeSampled(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > MAX_DIM * 2 || bounds.outHeight / sample > MAX_DIM * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDim || w <= 0 || h <= 0) return src
        val ratio = maxDim.toFloat() / longest.toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun sanitizeObjectId(objectId: String): String =
        objectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifEmpty { "thumb" }
}
