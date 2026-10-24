package au.com.dnscryptmonitor
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
object Notifications {
    const val CHANNEL = "blocked_domains"
    const val STATUS = "monitor_status"
    const val ACTION_ALLOW = "au.com.dnscryptmonitor.ALLOW"
    const val DOMAIN = "domain"
    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val n = c.getSystemService(NotificationManager::class.java)
            n.createNotificationChannel(NotificationChannel(CHANNEL, "Blocked domains", NotificationManager.IMPORTANCE_HIGH).apply { description = "DNSCrypt blocked-domain alerts" })
            n.createNotificationChannel(NotificationChannel(STATUS, "Monitor status", NotificationManager.IMPORTANCE_LOW))
        }
    }
    fun blocked(c: Context, id: Int, domain: String, pattern: String?) {
        val ai = Intent(c, AllowDomainReceiver::class.java).setAction(ACTION_ALLOW).putExtra(DOMAIN, domain)
        val ap = PendingIntent.getBroadcast(c, id, ai, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val oi = PendingIntent.getActivity(c, id + 100000, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = if (pattern.isNullOrBlank()) domain else "$domain\nRule: $pattern"
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("DNSCrypt blocked a domain").setContentText(domain)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(oi)
            .addAction(android.R.drawable.ic_input_add, "Allow", ap).build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
    fun status(c: Context) = NotificationCompat.Builder(c, STATUS).setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("DNSCrypt Monitor").setContentText("Monitoring blocked-names.log").setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
}
