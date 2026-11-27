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
)

data class Counts(
    val pending: Int = 0, val uploaded: Int = 0, val synced: Int = 0,
    val syncedBytes: Long = 0, val pendingBytes: Long = 0,
    /** Items the inbox will never accept (over the per-file limit). */
    val skipped: Int = 0,
)

class PhotoDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "photos.db", null, 3) {
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
                hash_size INTEGER
            )""",
        )
        db.execSQL("CREATE INDEX photos_state ON photos(state)")
        db.execSQL("CREATE UNIQUE INDEX photos_object ON photos(object_id)")
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
    }

    /** Adds a row if this media item isn't tracked yet. The object id is fixed up front so retries are idempotent. */
    fun addIfNew(mediaId: Long, isVideo: Boolean, name: String, size: Long, objectId: String, relPath: String = "DCIM/"): Boolean {
        val v = ContentValues().apply {
            put("media_id", mediaId); put("is_video", if (isVideo) 1 else 0)
            put("name", name); put("size", size); put("object_id", objectId); put("rel_path", relPath)
        }
        return writableDatabase.insertWithOnConflict("photos", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    private fun read(c: android.database.Cursor) = Photo(
        mediaId = c.getLong(0), isVideo = c.getInt(1) == 1, name = c.getString(2), size = c.getLong(3),
        state = c.getString(4), objectId = c.getString(5),
        uploadedAt = if (c.isNull(6)) null else c.getLong(6), syncedAt = if (c.isNull(7)) null else c.getLong(7),
        relPath = c.getString(8) ?: "", sha256 = if (c.isNull(9)) null else c.getString(9),
    )

    private val cols = "media_id,is_video,name,size,state,object_id,uploaded_at,synced_at,rel_path,sha256"

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

    fun clear() = writableDatabase.execSQL("DELETE FROM photos")
}
