package au.com.dnscryptmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BlocklistSource(
    val url: String,
    val selected: Boolean,
    val custom: Boolean,
    val sizeBytes: Long? = null
)

/** Saved blocklist sources, build options and schedule. Own preferences file; nothing else is touched. */
object BlocklistPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("blocklist_prefs", Context.MODE_PRIVATE)

    fun sources(c: Context): List<BlocklistSource> {
        val raw = sp(c).getString("sources", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                BlocklistSource(
                    o.getString("url"),
                    o.optBoolean("selected", false),
                    o.optBoolean("custom", false),
                    if (o.has("size")) o.getLong("size") else null
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveSources(c: Context, list: List<BlocklistSource>) {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject().put("url", it.url).put("selected", it.selected).put("custom", it.custom)
            it.sizeBytes?.let { s -> o.put("size", s) }
            arr.put(o)
        }
        sp(c).edit().putString("sources", arr.toString()).apply()
    }

    fun ignoreFailures(c: Context) = sp(c).getBoolean("ignore_failures", true)
    fun setIgnoreFailures(c: Context, v: Boolean) = sp(c).edit().putBoolean("ignore_failures", v).apply()

    fun timeoutSec(c: Context) = sp(c).getInt("timeout_sec", 30)
    fun setTimeoutSec(c: Context, v: Int) = sp(c).edit().putInt("timeout_sec", v).apply()

    /** Drop names already covered by a parent entry (the proxy blocks subdomains of a listed name anyway). */
    fun collapse(c: Context) = sp(c).getBoolean("collapse", true)
    fun setCollapse(c: Context, v: Boolean) = sp(c).edit().putBoolean("collapse", v).apply()

    fun scheduleEnabled(c: Context) = sp(c).getBoolean("sched_on", false)
    fun scheduleWeekly(c: Context) = sp(c).getBoolean("sched_weekly", false)
    fun scheduleHour(c: Context) = sp(c).getInt("sched_hour", 3)
    fun restartAfter(c: Context) = sp(c).getBoolean("sched_restart", true)
    fun setSchedule(c: Context, enabled: Boolean, weekly: Boolean, hour: Int, restartAfter: Boolean) {
        sp(c).edit().putBoolean("sched_on", enabled).putBoolean("sched_weekly", weekly)
            .putInt("sched_hour", hour).putBoolean("sched_restart", restartAfter).apply()
    }
}
