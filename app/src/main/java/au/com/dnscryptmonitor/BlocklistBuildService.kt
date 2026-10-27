package au.com.dnscryptmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** Progress shared between the build service and the Build Blocklist tab. */
object BlocklistStatus {
    data class State(
        val running: Boolean = false,
        val step: String = "",
        val log: List<String> = emptyList(),
        val lastSummary: String? = null,
        val lastOk: Boolean? = null,
        val offerRestart: Boolean = false
    )

    val state = MutableStateFlow(State())

    fun log(msg: String) = state.update { it.copy(step = msg, log = (it.log + msg).takeLast(30)) }
}

/** Runs the build in the foreground so it survives the app being closed. Used by the button and the schedule. */
class BlocklistBuildService : Service() {
    companion object {
        const val EXTRA_SCHEDULED = "scheduled"
        private const val CHANNEL = "blocklist_build"
        private const val ONGOING_ID = 9101
        private const val RESULT_ID = 9102
    }

    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        val scheduled = intent?.getBooleanExtra(EXTRA_SCHEDULED, false) ?: false

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Blocklist builds", NotificationManager.IMPORTANCE_LOW))
        val first = ongoing("Starting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ONGOING_ID, first)

        BlocklistStatus.state.update { it.copy(running = true, step = "Starting…", log = emptyList(), offerRestart = false) }

        Thread({
            var result = BlocklistBuilder.Result(false, "Build did not run.")
            try {
                result = BlocklistBuilder.build(this) { msg ->
                    BlocklistStatus.log(msg)
                    nm.notify(ONGOING_ID, ongoing(msg))
                }
            } catch (e: Throwable) {
                result = BlocklistBuilder.Result(false, "Build crashed: ${e.message}")
            }

            var restarted = false
            if (result.ok && scheduled && BlocklistPrefs.restartAfter(this)) {
                BlocklistStatus.log("Restarting dnscrypt-proxy")
                restarted = RootShell.run(ProxyControl.restartCommand(), 20000).code == 0
            }
            val summary = result.summary + if (restarted) " Proxy restarted." else ""

            BlocklistStatus.state.update {
                it.copy(
                    running = false,
                    step = if (result.ok) "Done" else "Failed",
                    lastSummary = summary,
                    lastOk = result.ok,
                    offerRestart = result.ok && !scheduled
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(
                RESULT_ID,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(if (result.ok) "Blocklist built" else "Blocklist build failed")
                    .setContentText(summary)
                    .setStyle(Notification.BigTextStyle().bigText(summary))
                    .setAutoCancel(true)
                    .build()
            )
            running.set(false)
            stopSelf()
        }, "blocklist-build").start()
        return START_NOT_STICKY
    }

    private fun ongoing(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Building blocklist")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
