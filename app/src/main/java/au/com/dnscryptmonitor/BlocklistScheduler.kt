package au.com.dnscryptmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Daily or weekly (Sunday) rebuild at roughly the chosen hour. Uses an inexact, Doze-friendly alarm, so it can run
 * a little late. The alarm re-arms itself every time it fires and is re-armed when the monitor service/boot starts.
 */
object BlocklistScheduler {
    private const val REQUEST = 4411

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REQUEST, Intent(c, BlocklistAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun nextTrigger(weekly: Boolean, hour: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (weekly) set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, if (weekly) 7 else 1)
        return cal.timeInMillis
    }

    fun rearm(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c))
        if (!BlocklistPrefs.scheduleEnabled(c)) return
        val t = nextTrigger(BlocklistPrefs.scheduleWeekly(c), BlocklistPrefs.scheduleHour(c))
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(c))
    }
}

class BlocklistAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BlocklistScheduler.rearm(context)
        val pendingResult = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val svc = Intent(app, BlocklistBuildService::class.java).putExtra(BlocklistBuildService.EXTRA_SCHEDULED, true)
                try {
                    ContextCompat.startForegroundService(app, svc)
                } catch (e: Exception) {
                    // Background start refused: root is always allowed to start it.
                    Log.w("DNSCryptMonitor", "Direct start refused, starting via root", e)
                    RootShell.run(
                        "am start-foreground-service -n ${app.packageName}/${BlocklistBuildService::class.java.name} " +
                            "--ez ${BlocklistBuildService.EXTRA_SCHEDULED} true", 20000
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
