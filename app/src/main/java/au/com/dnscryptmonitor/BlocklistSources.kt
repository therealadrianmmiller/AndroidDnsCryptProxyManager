package au.com.dnscryptmonitor

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object BlocklistSources {
    private const val HAGEZI_WILDCARD_API =
        "https://api.github.com/repos/hagezi/dns-blocklists/contents/wildcard"

    fun open(url: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "dnscrypt-monitor")
        }

    /** Raw download URLs of every file in hagezi's wildcard/ folder whose name contains "onlydomains". */
    fun fetchHagezi(timeoutMs: Int): List<String> {
        val conn = open(HAGEZI_WILDCARD_API, timeoutMs)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IOException("GitHub returned HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val url = o.optString("download_url")
                if (o.optString("type") == "file" && name.contains("onlydomains") && url.startsWith("https://")) out.add(url)
            }
            return out.sortedBy { it.substringAfterLast('/') }
        } finally {
            conn.disconnect()
        }
    }

    /** Size in bytes from a HEAD request, or null if the server doesn't say. */
    fun headSize(url: String, timeoutMs: Int): Long? {
        val conn = open(url, timeoutMs)
        return try {
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (conn.responseCode != 200) null else conn.contentLengthLong.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
