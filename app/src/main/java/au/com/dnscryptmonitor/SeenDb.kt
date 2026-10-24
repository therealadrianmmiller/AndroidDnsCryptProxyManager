package au.com.dnscryptmonitor
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
class SeenDb(c: Context) : SQLiteOpenHelper(c, "seen-domains.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE seen (h TEXT PRIMARY KEY, domain TEXT NOT NULL, first_seen INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun has(s: String): Boolean { readableDatabase.rawQuery("SELECT 1 FROM seen WHERE h=?", arrayOf(hash(s))).use { return it.moveToFirst() } }
    @Synchronized fun add(s: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO seen(h,domain,first_seen) VALUES(?,?,?)", arrayOf(hash(s), s.trim().lowercase(), System.currentTimeMillis())) }
    @Synchronized fun purge() { writableDatabase.delete("seen", null, null) }
    @Synchronized fun count(): Int { readableDatabase.rawQuery("SELECT COUNT(*) FROM seen", null).use { return if (it.moveToFirst()) it.getInt(0) else 0 } }
}
