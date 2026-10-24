package au.com.dnscryptmonitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class RecentBlockedDomain(
    val domain: String,
    val pattern: String?,
    val lastSeen: Long
)

class SeenDb(c: Context) : SQLiteOpenHelper(c, "seen-domains.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE seen (h TEXT PRIMARY KEY, domain TEXT NOT NULL, first_seen INTEGER NOT NULL)")
        createRecentTable(db)
    }

    private fun createRecentTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS recent_blocked (" +
                "domain_key TEXT PRIMARY KEY, domain TEXT NOT NULL, pattern TEXT, last_seen INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRecentTable(db)
    }

    private fun hash(s: String) =
        MessageDigest.getInstance("SHA-256")
            .digest(s.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Synchronized
    fun has(s: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM seen WHERE h=?", arrayOf(hash(s))).use {
            return it.moveToFirst()
        }
    }

    @Synchronized
    fun add(s: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO seen(h,domain,first_seen) VALUES(?,?,?)",
            arrayOf(hash(s), s.trim().lowercase(), System.currentTimeMillis())
        )
    }

    @Synchronized
    fun recordBlocked(domain: String, pattern: String?) {
        val clean = domain.trim()
        if (clean.isBlank()) return
        val values = ContentValues().apply {
            put("domain_key", clean.lowercase())
            put("domain", clean)
            if (pattern.isNullOrBlank()) putNull("pattern") else put("pattern", pattern)
            put("last_seen", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "recent_blocked", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun recent(limit: Int = 200): List<RecentBlockedDomain> {
        val result = mutableListOf<RecentBlockedDomain>()
        readableDatabase.rawQuery(
            "SELECT domain, pattern, last_seen FROM recent_blocked ORDER BY last_seen DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 1000).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    RecentBlockedDomain(
                        domain = cursor.getString(0),
                        pattern = if (cursor.isNull(1)) null else cursor.getString(1),
                        lastSeen = cursor.getLong(2)
                    )
                )
            }
        }
        return result
    }

    @Synchronized
    fun purge() {
        writableDatabase.delete("seen", null, null)
        writableDatabase.delete("recent_blocked", null, null)
    }

    @Synchronized
    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM seen", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
