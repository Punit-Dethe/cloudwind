package dev.cloudwind.app

import android.content.ContentResolver
import android.content.ContentUris
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

    /** Adds newly seen photos/videos to the database as `pending`. Returns how many were new. */
    fun scan(resolver: ContentResolver, db: PhotoDb, settings: Settings): Int {
        var added = 0
        for ((uri, isVideo) in listOf(images to false, videos to true)) {
            val selection = buildString {
                append("${MediaStore.MediaColumns.DATE_ADDED} >= ?")
                if (!settings.allFolders) append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/%'")
            }
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                selection,
                arrayOf(settings.since.toString()),
                "${MediaStore.MediaColumns._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val size = c.getLong(2)
                    if (size <= 0) continue
                    if (db.addIfNew(c.getLong(0), isVideo, c.getString(1) ?: "media", size, WorkerClient.newObjectId())) added++
                }
            }
        }
        return added
    }
}
