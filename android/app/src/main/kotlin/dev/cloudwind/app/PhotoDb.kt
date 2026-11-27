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
)

data class Counts(
    val pending: Int = 0, val uploaded: Int = 0, val synced: Int = 0,
    val syncedBytes: Long = 0, val pendingBytes: Long = 0,
    /** Items the inbox will never accept (over the per-file limit). */
    val skipped: Int = 0,
)

class PhotoDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "photos.db", null, 1) {
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
                error TEXT
            )""",
        )
        db.execSQL("CREATE INDEX photos_state ON photos(state)")
        db.execSQL("CREATE UNIQUE INDEX photos_object ON photos(object_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Adds a row if this media item isn't tracked yet. The object id is fixed up front so retries are idempotent. */
    fun addIfNew(mediaId: Long, isVideo: Boolean, name: String, size: Long, objectId: String): Boolean {
        val v = ContentValues().apply {
            put("media_id", mediaId); put("is_video", if (isVideo) 1 else 0)
            put("name", name); put("size", size); put("object_id", objectId)
        }
        return writableDatabase.insertWithOnConflict("photos", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    private fun read(c: android.database.Cursor) = Photo(
        mediaId = c.getLong(0), isVideo = c.getInt(1) == 1, name = c.getString(2), size = c.getLong(3),
        state = c.getString(4), objectId = c.getString(5),
        uploadedAt = if (c.isNull(6)) null else c.getLong(6), syncedAt = if (c.isNull(7)) null else c.getLong(7),
    )

    private val cols = "media_id,is_video,name,size,state,object_id,uploaded_at,synced_at"

    /** `newestFirst` makes recent photos reach the cloud before an old backlog. */
    fun byState(state: String, limit: Int = 100_000, newestFirst: Boolean = false): List<Photo> =
        readableDatabase.rawQuery("SELECT $cols FROM photos WHERE state=? ORDER BY media_id ${if (newestFirst) "DESC" else "ASC"} LIMIT $limit", arrayOf(state)).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    fun markUploaded(mediaId: Long, at: Long) {
        writableDatabase.execSQL("UPDATE photos SET state='uploaded', uploaded_at=?, error=NULL WHERE media_id=? AND state='pending'", arrayOf(at, mediaId))
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

    fun clear() = writableDatabase.execSQL("DELETE FROM photos")
}
