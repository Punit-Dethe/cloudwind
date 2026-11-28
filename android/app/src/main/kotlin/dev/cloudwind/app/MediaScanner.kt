package dev.cloudwind.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.cloudwind.core.WorkerClient

data class DeviceFolder(
    val relPath: String,
    val displayName: String,
    val itemCount: Int,
    val totalBytes: Long,
    val isSaf: Boolean = false,
)

object MediaScanner {
    private val images: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videos: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun uriFor(mediaId: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(if (isVideo) videos else images, mediaId)

    /** True if the media item still exists on the phone (handles both MediaStore and SAF items). */
    fun exists(resolver: ContentResolver, p: Photo): Boolean =
        if (p.docUri != null) {
            try {
                resolver.query(Uri.parse(p.docUri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } catch (_: Exception) {
                false
            }
        } else {
            exists(resolver, p.mediaId, p.isVideo)
        }

    /** True if the MediaStore item still exists on the phone. */
    fun exists(resolver: ContentResolver, mediaId: Long, isVideo: Boolean): Boolean =
        resolver.query(uriFor(mediaId, isVideo), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false

    /**
     * Automatically discovers every photo/video folder on the phone via MediaStore (Camera, WhatsApp
     * Images/Video, Telegram, Canva, Screenshots, Downloads, etc.), plus any manually picked SAF folders.
     */
    fun discoverDeviceFolders(context: Context, settings: Settings): List<DeviceFolder> {
        val resolver = context.contentResolver
        data class Acc(var displayName: String, var count: Int, var bytes: Long)
        val found = LinkedHashMap<String, Acc>()

        try {
            for (volume in MediaStore.getExternalVolumeNames(context)) {
                val uris = listOf(
                    MediaStore.Images.Media.getContentUri(volume),
                    MediaStore.Video.Media.getContentUri(volume),
                )
                for (uri in uris) {
                    resolver.query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE,
                        ),
                        null,
                        null,
                        null,
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val size = c.getLong(2)
                            if (size <= 0L) continue
                            val normPath = FolderSelection.norm(c.getString(0) ?: "")
                            if (normPath.isEmpty()) continue
                            val bucket = c.getString(1)
                            val acc = found.getOrPut(normPath) {
                                Acc(FolderSelection.displayName(normPath, bucket), 0, 0L)
                            }
                            acc.count++
                            acc.bytes += size
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            // Photo access not granted yet.
        }

        val out = found.entries.map { (path, acc) ->
            DeviceFolder(relPath = path, displayName = acc.displayName, itemCount = acc.count, totalBytes = acc.bytes, isSaf = false)
        }.toMutableList()

        // Include manually added SAF folders that aren't already visible in MediaStore (e.g. empty or .nomedia folders).
        for ((normPath, treeUriStr) in settings.safFolders()) {
            if (normPath in found) continue
            val (cnt, bytes) = countSafMedia(resolver, treeUriStr)
            out += DeviceFolder(
                relPath = normPath,
                displayName = FolderSelection.displayName(normPath),
                itemCount = cnt,
                totalBytes = bytes,
                isSaf = true,
            )
        }

        return out.sortedWith(
            compareByDescending<DeviceFolder> { it.relPath == "DCIM/Camera" }
                .thenByDescending { it.itemCount }
                .thenBy { it.displayName.lowercase() },
        )
    }

    /**
     * Adds newly seen photos/videos in selected folders as `pending`, and proactively re-queues/refreshes
     * items edited in place. Also scans any manually added SAF folders not indexed by MediaStore (`.nomedia`).
     */
    fun scan(context: Context, db: PhotoDb, settings: Settings): Int {
        val resolver = context.contentResolver
        var changed = 0
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val version = MediaStore.getVersion(context, volume)
            val from = settings.scanGeneration(volume, version)
            val upTo = MediaStore.getGeneration(context, volume) // read before querying so nothing slips between
            var safeUpTo = upTo
            for ((uri, isVideo) in listOf(MediaStore.Images.Media.getContentUri(volume) to false, MediaStore.Video.Media.getContentUri(volume) to true)) {
                val selection = "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?"
                resolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.GENERATION_MODIFIED,
                    ),
                    selection,
                    arrayOf(from.toString(), settings.since.toString()),
                    "${MediaStore.MediaColumns._ID} ASC",
                )?.use { c ->
                    db.inTransaction {
                        while (c.moveToNext()) {
                            val rawRel = c.getString(3) ?: ""
                            if (!settings.isFolderSelected(rawRel)) continue
                            val size = c.getLong(2)
                            val rowGen = c.getLong(5)
                            if (size <= 0) {
                                // Still being written by the camera: never advance scan_gen past this row.
                                safeUpTo = minOf(safeUpTo, (rowGen - 1).coerceAtLeast(from))
                                continue
                            }
                            val dateMod = c.getLong(4)
                            if (db.upsertScanned(
                                    mediaId = c.getLong(0),
                                    isVideo = isVideo,
                                    name = c.getString(1) ?: "media",
                                    size = size,
                                    dateModified = dateMod,
                                    newObjectId = WorkerClient.newObjectId(),
                                    relPath = rawRel,
                                )
                            ) changed++
                        }
                    }
                }
            }
            settings.setScanGeneration(volume, version, safeUpTo)
        }

        // Fallback scan for manually added SAF folders that MediaStore does not index (e.g. `.nomedia` / WhatsApp Sent).
        val safFolders = settings.safFolders()
        if (safFolders.isNotEmpty()) {
            for ((normPath, treeUriStr) in safFolders) {
                if (!settings.isFolderSelected(normPath)) continue
                if (mediaStoreHasFolder(resolver, context, normPath)) continue
                changed += scanSafFolder(resolver, db, settings, normPath, treeUriStr)
            }
        }
        return changed
    }

    private fun mediaStoreHasFolder(resolver: ContentResolver, context: Context, normPath: String): Boolean {
        val withSlash = "$normPath/"
        return try {
            for (volume in MediaStore.getExternalVolumeNames(context)) {
                for (uri in listOf(MediaStore.Images.Media.getContentUri(volume), MediaStore.Video.Media.getContentUri(volume))) {
                    val has = resolver.query(
                        uri,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf(withSlash, normPath),
                        null,
                    )?.use { it.moveToFirst() } ?: false
                    if (has) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun countSafMedia(resolver: ContentResolver, treeUriStr: String): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        try {
            val treeUri = Uri.parse(treeUriStr)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val mime = c.getString(1)
                    val size = c.getLong(2)
                    if (size <= 0L) continue
                    if (FolderSelection.isSupportedMedia(mime, name)) {
                        count++
                        bytes += size
                    }
                }
            }
        } catch (_: Exception) {}
        return count to bytes
    }

    private fun scanSafFolder(
        resolver: ContentResolver,
        db: PhotoDb,
        settings: Settings,
        normPath: String,
        treeUriStr: String,
    ): Int {
        var changed = 0
        try {
            val treeUri = Uri.parse(treeUriStr)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { c ->
                db.inTransaction {
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        val size = c.getLong(3)
                        if (size <= 0L || !FolderSelection.isSupportedMedia(mime, name)) continue
                        val lastModSecs = c.getLong(4) / 1000L
                        if (settings.since > 0L && lastModSecs in 1 until settings.since) continue
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                        val isVideo = mime?.startsWith("video/", ignoreCase = true) == true ||
                            name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "mkv", "webm", "3gp", "m4v")
                        if (db.upsertScanned(
                                mediaId = FolderSelection.safMediaId(docUri),
                                isVideo = isVideo,
                                name = name,
                                size = size,
                                dateModified = lastModSecs,
                                newObjectId = WorkerClient.newObjectId(),
                                relPath = "$normPath/",
                                docUri = docUri,
                            )
                        ) changed++
                    }
                }
            }
        } catch (_: Exception) {}
        return changed
    }

    /** Current size of a media item as MediaStore reports it, or null if it is gone. */
    fun currentSize(resolver: ContentResolver, mediaId: Long, isVideo: Boolean): Long? =
        resolver.query(uriFor(mediaId, isVideo), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
}
