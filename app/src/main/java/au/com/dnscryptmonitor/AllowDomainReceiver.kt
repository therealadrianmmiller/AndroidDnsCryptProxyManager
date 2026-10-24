package au.com.dnscryptmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object DomainAllow {
    fun allow(context: Context, domainInput: String): ShellResult {
        val domain = domainInput.trim()
        if (domain.isBlank() || domain.contains('\n') || domain.contains('\r')) {
            return ShellResult(2, "Invalid domain")
        }
        val path = RootShell.q(Prefs.allow(context))
        val quotedDomain = RootShell.q(domain)
        val append = "touch $path && if ! grep -Fxi -- $quotedDomain $path >/dev/null 2>&1; then printf '%s\\n' $quotedDomain >> $path; fi"
        return RootShell.run(ProxyControl.restartCommand(append), 20000)
    }
}

class AllowDomainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_ALLOW) return
        val domain = intent.getStringExtra(Notifications.DOMAIN)?.trim().orEmpty()
        if (domain.isBlank()) return
        // Root shell calls block, so keep them off the main thread.
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val result = DomainAllow.allow(appContext, domain)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        if (result.code == 0) "Allowed $domain; proxy restart requested."
                        else "Allow failed: ${result.output}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
