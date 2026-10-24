package au.com.dnscryptmonitor
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
class AllowDomainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_ALLOW) return
        val d = intent.getStringExtra(Notifications.DOMAIN)?.trim().orEmpty()
        if (d.isBlank() || d.contains('\n')) return
        val path = RootShell.q(Prefs.allow(context)); val domain = RootShell.q(d)
        val cmd = "touch $path; if ! grep -Fxi -- $domain $path >/dev/null 2>&1; then printf '%s\\n' $domain >> $path; fi"
        val r = RootShell.run(cmd)
        Toast.makeText(context, if (r.code == 0) "Allowed $d. Restart proxy to apply." else "Allow failed: ${r.output}", Toast.LENGTH_LONG).show()
    }
}
