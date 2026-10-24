package au.com.dnscryptmonitor

import android.content.Context

/** UI-only preferences (kept separate from Prefs so nothing else needs to change). */
object UiPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    /** "list" (default), "compact" or "classic". */
    fun buttonStyle(c: Context): String = sp(c).getString("button_style", "list") ?: "list"
    fun setButtonStyle(c: Context, v: String) = sp(c).edit().putString("button_style", v).apply()
}
