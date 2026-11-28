package dev.cloudwind.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One row per photo/video the app tracks. States: pending -> uploaded -> synced, or skipped (too large). */
data class Photo(
    val mediaId: Long,
    val isVideo: Boolean,
    val name: String,
    val size: Long,
    val state: String,
    val objectId: String,
    val uploadedAt: Long?,
    val syncedAt: Long?,
    /** Folder the file lives in, e.g. "DCIM/Camera/". Drives the cloud-OK / Home-only policy. */
    val relPath: String = "",
    /** Cached SHA-256 of the file's bytes (computed when a direct transfer needs it). */
    val sha256: String? = null,
    /** Last modified time from MediaStore (epoch seconds); 0 for legacy pre-v4 rows. */
    val dateModified: Long = 0,
    /** Non-null only for files read via SAF (e.g. inside `.nomedia` folders); null for normal MediaStore items. */
    val docUri: String? = null,
)

data class FolderStats(
    val path: String,
    val total: Int,
    val pending: Int,
    val uploaded: Int,
    val synced: Int,
    val totalBytes: Long,
)

data class Counts(
    val pending: Int = 0, val uploaded: Int = 0, val synced: Int = 0,
    val syncedBytes: Long = 0, val pendingBytes: Long = 0,
    /** Items the inbox will never accept (over the per-file limit). */
    val skipped: Int = 0,
)

class PhotoDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "photos.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE photos (
                media_id INTEGER PRIMARY KEY,
                is_video INTEGER NOT NULL,
                name TEXT NOT NULL,
                size INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'pending',
                object_id TEXT NOT NULL,
                uploaded_at INTEGER,
                synced_at INTEGER,
                error TEXT,
                stage_size INTEGER,
                resume TEXT,
                rel_path TEXT NOT NULL DEFAULT 'DCIM/',
                sha256 TEXT,
                hash_size INTEGER,
                date_modified INTEGER NOT NULL DEFAULT 0,
                doc_uri TEXT
            )""",
        )
        db.execSQL("CREATE INDEX photos_state ON photos(state)")
        db.execSQL("CREATE UNIQUE INDEX photos_object ON photos(object_id)")
        createHistoryTable(db)
    }

    private fun createHistoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id INTEGER NOT NULL,
                doc_uri TEXT,
                object_id TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                rel_path TEXT NOT NULL DEFAULT 'DCIM/',
                size INTEGER NOT NULL,
                is_video INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                route TEXT NOT NULL,
                status TEXT NOT NULL,
                transferred_at INTEGER NOT NULL,
                verified_at INTEGER,
                local_removed INTEGER NOT NULL DEFAULT 0,
                thumb_file TEXT
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS history_transferred ON history(transferred_at DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS history_media ON history(media_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE photos ADD COLUMN stage_size INTEGER")
            db.execSQL("ALTER TABLE photos ADD COLUMN resume TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE photos ADD COLUMN rel_path TEXT NOT NULL DEFAULT 'DCIM/'")
            db.execSQL("ALTER TABLE photos ADD COLUMN sha256 TEXT")
            db.execSQL("ALTER TABLE photos ADD COLUMN hash_size INTEGER")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE photos ADD COLUMN date_modified INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE photos ADD COLUMN doc_uri TEXT")
        }
        if (oldVersion < 6) {
            createHistoryTable(db)
            // Seed history from already uploaded/synced rows so upgrading users immediately see their recent backups.
            db.execSQL(
                """INSERT OR IGNORE INTO history (
                    media_id, doc_uri, object_id, name, rel_path, size, is_video, duration_ms,
                    route, status, transferred_at, verified_at, local_removed, thumb_file
                )
                SELECT
                    media_id,
                    doc_uri,
                    object_id,
                    name,
                    rel_path,
                    size,
                    is_video,
                    0,
                    CASE WHEN uploaded_at IS NOT NULL THEN 'cloud' ELSE 'direct' END,
                    CASE WHEN state = 'synced' THEN 'pc' ELSE 'cloud' END,
                    COALESCE(synced_at, uploaded_at, date_modified, 0),
                    synced_at,
                    0,
                    NULL
                FROM photos
                WHERE state IN ('uploaded', 'synced')""",
            )
        }
    }

    /** Adds a row if this media item isn't tracked yet. The object id is fixed up front so retries are idempotent. */
    fun addIfNew(
        mediaId: Long,
        isVideo: Boolean,
        name: String,
        size: Long,
        objectId: String,
        relPath: String = "DCIM/",
        dateModified: Long = 0,
        docUri: String? = null,
    ): Boolean {
        val v = ContentValues().apply {
            put("media_id", mediaId); put("is_video", if (isVideo) 1 else 0)
            put("name", name); put("size", size); put("object_id", objectId); put("rel_path", relPath)
            put("date_modified", dateModified)
            if (docUri != null) put("doc_uri", docUri)
        }
        return writableDatabase.insertWithOnConflict("photos", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /**
     * Insert a new item or react to an in-place edit detected via MediaStore `GENERATION_MODIFIED` or SAF scan.
     * Returns true if the item is newly added or re-queued/refreshed for backup.
     */
    fun upsertScanned(
        mediaId: Long,
        isVideo: Boolean,
        name: String,
        size: Long,
        dateModified: Long,
        newObjectId: String,
        relPath: String = "DCIM/",
        docUri: String? = null,
    ): Boolean {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT state, size, date_modified FROM photos WHERE media_id=?",
            arrayOf(mediaId.toString()),
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getLong(2)) else null
        }
        if (existing == null) {
            return addIfNew(mediaId, isVideo, name, size, newObjectId, relPath, dateModified, docUri)
        }
        val (state, oldSize, oldMod) = existing
        return when (EditDetector.classify(state, oldSize, oldMod, size, dateModified)) {
            ScanAction.KEEP -> {
                // Backfill date_modified on legacy pre-v4 rows without triggering a re-upload.
                if (oldMod == 0L && dateModified > 0L) {
                    db.execSQL("UPDATE photos SET date_modified=? WHERE media_id=?", arrayOf(dateModified, mediaId))
                }
                false
            }
            ScanAction.REFRESH_PENDING -> {
                db.execSQL(
                    "UPDATE photos SET name=?, size=?, date_modified=?, rel_path=?, doc_uri=?, sha256=NULL, hash_size=NULL, stage_size=NULL, resume=NULL, error=NULL WHERE media_id=?",
                    arrayOf(name, size, dateModified, relPath, docUri, mediaId),
                )
                true
            }
            ScanAction.REQUEUE_EDITED -> {
                db.execSQL(
                    "UPDATE photos SET state='pending', name=?, size=?, date_modified=?, rel_path=?, doc_uri=?, object_id=?, uploaded_at=NULL, synced_at=NULL, sha256=NULL, hash_size=NULL, stage_size=NULL, resume=NULL, error=NULL WHERE media_id=?",
                    arrayOf(name, size, dateModified, relPath, docUri, newObjectId, mediaId),
                )
                true
            }
        }
    }

    /** Remove pending (not yet uploaded) items belonging to a folder that the user deselected. */
    fun removePendingInFolder(normRelPath: String) {
        val withSlash = "$normRelPath/"
        writableDatabase.execSQL(
            "DELETE FROM photos WHERE state='pending' AND (rel_path=? OR rel_path=?)",
            arrayOf(normRelPath, withSlash),
        )
    }

    private fun read(c: android.database.Cursor) = Photo(
        mediaId = c.getLong(0), isVideo = c.getInt(1) == 1, name = c.getString(2), size = c.getLong(3),
        state = c.getString(4), objectId = c.getString(5),
        uploadedAt = if (c.isNull(6)) null else c.getLong(6), syncedAt = if (c.isNull(7)) null else c.getLong(7),
        relPath = c.getString(8) ?: "", sha256 = if (c.isNull(9)) null else c.getString(9),
        dateModified = c.getLong(10),
        docUri = if (c.isNull(11)) null else c.getString(11),
    )

    private val cols = "media_id,is_video,name,size,state,object_id,uploaded_at,synced_at,rel_path,sha256,date_modified,doc_uri"

    /** `newestFirst` makes recent photos reach the cloud before an old backlog. */
    fun byState(state: String, limit: Int = 100_000, newestFirst: Boolean = false): List<Photo> =
        readableDatabase.rawQuery("SELECT $cols FROM photos WHERE state=? ORDER BY media_id ${if (newestFirst) "DESC" else "ASC"} LIMIT $limit", arrayOf(state)).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    fun markUploaded(mediaId: Long, at: Long) {
        writableDatabase.execSQL("UPDATE photos SET state='uploaded', uploaded_at=?, error=NULL WHERE media_id=? AND state='pending'", arrayOf(at, mediaId))
    }

    /** Rows still waiting on the PC (not yet confirmed): newest first, for building a direct-transfer plan. */
    fun unsynced(): List<Photo> =
        readableDatabase.rawQuery("SELECT $cols FROM photos WHERE state IN ('pending','uploaded') ORDER BY media_id DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    /** The cached hash, only if it was computed for a file of this size (an edited file invalidates it). */
    fun cachedHash(mediaId: Long, size: Long): String? =
        readableDatabase.rawQuery("SELECT sha256, hash_size FROM photos WHERE media_id=?", arrayOf(mediaId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1) && c.getLong(1) == size) c.getString(0) else null
        }

    fun setHash(mediaId: Long, sha256: String, size: Long) {
        writableDatabase.execSQL("UPDATE photos SET sha256=?, hash_size=? WHERE media_id=?", arrayOf(sha256, size, mediaId))
    }

    fun clearHash(mediaId: Long) {
        writableDatabase.execSQL("UPDATE photos SET sha256=NULL, hash_size=NULL WHERE media_id=?", arrayOf(mediaId))
    }

    /** The PC confirmed these bytes directly (or already had them). */
    fun markSyncedDirect(mediaId: Long, at: Long) {
        writableDatabase.execSQL(
            "UPDATE photos SET state='synced', synced_at=?, error=NULL, stage_size=NULL, resume=NULL WHERE media_id=? AND state IN ('pending','uploaded')",
            arrayOf(at, mediaId),
        )
    }

    /** Distinct folders with how many tracked items each holds, for the folder-policy screen. */
    fun folders(): List<Pair<String, Int>> =
        readableDatabase.rawQuery("SELECT rel_path, COUNT(*) FROM photos GROUP BY rel_path ORDER BY rel_path", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }

    /** Per-folder breakdown (counts by state + total size) so the Folders tab shows live status per folder. */
    fun folderStats(): List<FolderStats> =
        readableDatabase.rawQuery(
            """SELECT rel_path,
                      COUNT(*),
                      SUM(CASE WHEN state='pending' THEN 1 ELSE 0 END),
                      SUM(CASE WHEN state='uploaded' THEN 1 ELSE 0 END),
                      SUM(CASE WHEN state='synced' THEN 1 ELSE 0 END),
                      COALESCE(SUM(size), 0)
               FROM photos
               GROUP BY rel_path
               ORDER BY COUNT(*) DESC, rel_path ASC""",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        FolderStats(
                            path = c.getString(0) ?: "",
                            total = c.getInt(1),
                            pending = c.getInt(2),
                            uploaded = c.getInt(3),
                            synced = c.getInt(4),
                            totalBytes = c.getLong(5),
                        ),
                    )
                }
            }
        }

    /** Permanently set aside (e.g. bigger than the inbox accepts); never retried. */
    fun markSkipped(mediaId: Long, reason: String) {
        writableDatabase.execSQL("UPDATE photos SET state='skipped', error=? WHERE media_id=? AND state='pending'", arrayOf(reason, mediaId))
    }

    fun setError(mediaId: Long, error: String) {
        writableDatabase.execSQL("UPDATE photos SET error=? WHERE media_id=?", arrayOf(error, mediaId))
    }

    /** Applies the Worker's view: anything it says is synced becomes synced here. */
    fun markSynced(objectId: String, at: Long) {
        writableDatabase.execSQL(
            "UPDATE photos SET state='synced', synced_at=? WHERE object_id=? AND state IN ('pending','uploaded')",
            arrayOf(at, objectId),
        )
    }

    fun oldestUploadedAt(): Long? =
        readableDatabase.rawQuery("SELECT MIN(uploaded_at) FROM photos WHERE state='uploaded'", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun remove(mediaIds: Collection<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            mediaIds.forEach { db.execSQL("DELETE FROM photos WHERE media_id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun counts(): Counts = readableDatabase.rawQuery(
        "SELECT state, COUNT(*), COALESCE(SUM(size),0) FROM photos GROUP BY state", null,
    ).use { c ->
        var r = Counts()
        while (c.moveToNext()) {
            when (c.getString(0)) {
                "pending" -> r = r.copy(pending = c.getInt(1), pendingBytes = c.getLong(2))
                "uploaded" -> r = r.copy(uploaded = c.getInt(1))
                "synced" -> r = r.copy(synced = c.getInt(1), syncedBytes = c.getLong(2))
                "skipped" -> r = r.copy(skipped = c.getInt(1))
            }
        }
        r
    }

    // ---- staged encrypted copy + multipart progress (so big uploads resume) -----------------

    fun stageSize(mediaId: Long): Long? =
        readableDatabase.rawQuery("SELECT stage_size FROM photos WHERE media_id=?", arrayOf(mediaId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun resumeJson(mediaId: Long): String? =
        readableDatabase.rawQuery("SELECT resume FROM photos WHERE media_id=?", arrayOf(mediaId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    fun setStage(mediaId: Long, size: Long) {
        writableDatabase.execSQL("UPDATE photos SET stage_size=?, resume=NULL WHERE media_id=?", arrayOf(size, mediaId))
    }

    fun setResume(mediaId: Long, json: String?) {
        writableDatabase.execSQL("UPDATE photos SET resume=? WHERE media_id=?", arrayOf(json, mediaId))
    }

    fun clearStage(mediaId: Long) {
        writableDatabase.execSQL("UPDATE photos SET stage_size=NULL, resume=NULL WHERE media_id=?", arrayOf(mediaId))
    }

    /** Object ids that legitimately have a staged file on disk. */
    fun stagedObjectIds(): Set<String> =
        readableDatabase.rawQuery("SELECT object_id FROM photos WHERE state='pending' AND stage_size IS NOT NULL", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    /** Record the exact number of bytes that were backed up, so "safe to delete" can check nothing changed since. */
    fun setSize(mediaId: Long, size: Long) {
        writableDatabase.execSQL("UPDATE photos SET size=? WHERE media_id=?", arrayOf(size, mediaId))
    }

    /** The file changed after it was backed up: back up the new version as a new object. */
    fun requeue(mediaId: Long, size: Long, newObjectId: String) {
        writableDatabase.execSQL(
            "UPDATE photos SET state='pending', size=?, object_id=?, uploaded_at=NULL, synced_at=NULL, sha256=NULL, hash_size=NULL, stage_size=NULL, resume=NULL, error=NULL WHERE media_id=?",
            arrayOf(size, newObjectId, mediaId),
        )
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM photos")
        writableDatabase.execSQL("DELETE FROM history")
    }

    // ---- persistent History log (survives phone cleanup) -----------------------------------

    fun recordHistory(
        p: Photo,
        route: String,
        status: String,
        transferredAt: Long,
        verifiedAt: Long?,
        thumbFile: String?,
        durationMs: Long = 0L,
    ) {
        val db = writableDatabase
        val existingThumb = db.rawQuery(
            "SELECT thumb_file, duration_ms FROM history WHERE object_id=?",
            arrayOf(p.objectId),
        ).use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) null else c.getString(0)) to c.getLong(1) else null
        }
        val finalThumb = thumbFile ?: existingThumb?.first
        val finalDur = if (durationMs > 0L) durationMs else (existingThumb?.second ?: 0L)
        val v = ContentValues().apply {
            put("media_id", p.mediaId)
            if (p.docUri != null) put("doc_uri", p.docUri) else putNull("doc_uri")
            put("object_id", p.objectId)
            put("name", p.name)
            put("rel_path", p.relPath)
            put("size", p.size)
            put("is_video", if (p.isVideo) 1 else 0)
            put("duration_ms", finalDur)
            put("route", route)
            put("status", status)
            put("transferred_at", transferredAt)
            if (verifiedAt != null) put("verified_at", verifiedAt) else putNull("verified_at")
            put("local_removed", 0)
            if (finalThumb != null) put("thumb_file", finalThumb) else putNull("thumb_file")
        }
        db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markHistorySyncedByObjectId(objectId: String, verifiedAt: Long) {
        writableDatabase.execSQL(
            "UPDATE history SET status='pc', verified_at=? WHERE object_id=?",
            arrayOf(verifiedAt, objectId),
        )
    }

    fun markHistoryLocalRemoved(mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            mediaIds.forEach { id ->
                db.execSQL("UPDATE history SET local_removed=1 WHERE media_id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setHistoryThumb(id: Long, thumbFile: String?, durationMs: Long) {
        writableDatabase.execSQL(
            "UPDATE history SET thumb_file=COALESCE(?, thumb_file), duration_ms=CASE WHEN ?>0 THEN ? ELSE duration_ms END WHERE id=?",
            arrayOf(thumbFile, durationMs, durationMs, id),
        )
    }

    fun historyList(limit: Int = 500): List<HistoryItem> =
        readableDatabase.rawQuery(
            """SELECT id, media_id, doc_uri, object_id, name, rel_path, size, is_video, duration_ms,
                      route, status, transferred_at, verified_at, local_removed, thumb_file
               FROM history
               ORDER BY transferred_at DESC, id DESC
               LIMIT $limit""",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        HistoryItem(
                            id = c.getLong(0),
                            mediaId = c.getLong(1),
                            docUri = if (c.isNull(2)) null else c.getString(2),
                            objectId = c.getString(3),
                            name = c.getString(4),
                            relPath = c.getString(5) ?: "",
                            size = c.getLong(6),
                            isVideo = c.getInt(7) == 1,
                            durationMs = c.getLong(8),
                            route = c.getString(9) ?: "direct",
                            status = c.getString(10) ?: "pc",
                            transferredAt = c.getLong(11),
                            verifiedAt = if (c.isNull(12)) null else c.getLong(12),
                            localRemoved = c.getInt(13) == 1,
                            thumbFile = if (c.isNull(14)) null else c.getString(14),
                        ),
                    )
                }
            }
        }

    fun thumbFilesNewestFirst(): List<String> =
        readableDatabase.rawQuery(
            "SELECT thumb_file FROM history WHERE thumb_file IS NOT NULL ORDER BY transferred_at DESC, id DESC",
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } }
        }

    fun clearThumbFiles(thumbFiles: Collection<String>) {
        if (thumbFiles.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            thumbFiles.forEach { name ->
                db.execSQL("UPDATE history SET thumb_file=NULL WHERE thumb_file=?", arrayOf(name))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
