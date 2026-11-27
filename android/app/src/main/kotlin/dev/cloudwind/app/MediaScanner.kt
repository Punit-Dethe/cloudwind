package dev.cloudwind.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.cloudwind.core.WorkerClient

object MediaScanner {
    private val images: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videos: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun uriFor(mediaId: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(if (isVideo) videos else images, mediaId)

    /** True if the media item still exists on the phone. */
    fun exists(resolver: ContentResolver, mediaId: Long, isVideo: Boolean): Boolean =
        resolver.query(uriFor(mediaId, isVideo), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false

    /**
     * Adds newly seen photos/videos to the database as `pending`. Returns how many were new.
     *
     * Incremental: MediaStore keeps a per-volume change counter (GENERATION_MODIFIED). Only items changed since
     * the last scan are read, which also catches a video that was still recording (hidden as "pending") when
     * its ID was assigned. An ID cursor would skip that video forever once a newer item had been seen.
     */
    fun scan(context: Context, db: PhotoDb, settings: Settings): Int {
        val resolver = context.contentResolver
        var added = 0
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val version = MediaStore.getVersion(context, volume)
            val from = settings.scanGeneration(volume, version)
            val upTo = MediaStore.getGeneration(context, volume) // read before querying so nothing slips between
            for ((uri, isVideo) in listOf(MediaStore.Images.Media.getContentUri(volume) to false, MediaStore.Video.Media.getContentUri(volume) to true)) {
                val selection = buildString {
                    append("${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?")
                    if (!settings.allFolders) append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/%'")
                }
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH),
                    selection,
                    arrayOf(from.toString(), settings.since.toString()),
                    "${MediaStore.MediaColumns._ID} ASC",
                )?.use { c ->
                    db.inTransaction {
                        while (c.moveToNext()) {
                            val size = c.getLong(2)
                            if (size <= 0) continue
                            if (db.addIfNew(c.getLong(0), isVideo, c.getString(1) ?: "media", size, WorkerClient.newObjectId(), c.getString(3) ?: "")) added++
                        }
                    }
                }
            }
            settings.setScanGeneration(volume, version, upTo)
        }
        return added
    }

    /** Current size of a media item as MediaStore reports it, or null if it is gone. */
    fun currentSize(resolver: ContentResolver, mediaId: Long, isVideo: Boolean): Long? =
        resolver.query(uriFor(mediaId, isVideo), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
}
