package au.com.dnscryptmonitor
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) try { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) } catch (_: Exception) {}
    }
}
