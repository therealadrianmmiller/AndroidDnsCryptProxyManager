package au.com.dnscryptmonitor
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (e: Exception) {
            // Logged instead of swallowed, so a blocked boot start shows up in logcat.
            Log.e("DNSCryptMonitor", "Could not start MonitorService at boot", e)
        }
    }
}
