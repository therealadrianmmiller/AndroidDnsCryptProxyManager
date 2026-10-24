package au.com.dnscryptmonitor
import android.content.Context
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("dnscrypt_monitor", Context.MODE_PRIVATE)
    fun base(c: Context) = p(c).getString("base", "/data/adb/dnscrypt-proxy")!!
    fun service(c: Context) = p(c).getString("service", "dnscrypt-proxy")!!
    fun theme(c: Context) = p(c).getString("theme", "system")!!
    fun setBase(c: Context, v: String) = p(c).edit().putString("base", v.trim()).apply()
    fun setService(c: Context, v: String) = p(c).edit().putString("service", v.trim()).apply()
    fun setTheme(c: Context, v: String) = p(c).edit().putString("theme", v).apply()
    fun log(c: Context) = "${base(c).trimEnd('/')}/blocked-names.log"
    fun allow(c: Context) = "${base(c).trimEnd('/')}/allowed-names.txt"
    fun block(c: Context) = "${base(c).trimEnd('/')}/blocked-names.txt"
}
