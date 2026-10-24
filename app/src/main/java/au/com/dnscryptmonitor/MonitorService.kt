package au.com.dnscryptmonitor
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
class MonitorService : Service() {
    private val running = AtomicBoolean(false)
    private var tail: Process? = null
    private var worker: Thread? = null
    private lateinit var db: SeenDb
    override fun onCreate() { super.onCreate(); Notifications.channels(this); db = SeenDb(this); startForeground(9001, Notifications.status(this)) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) { worker = Thread({ loop() }, "dnscrypt-log-monitor").apply { isDaemon = true; start() } }
        return START_STICKY
    }
    private fun loop() {
        while (running.get()) {
            try {
                val p = ProcessBuilder("su", "-c", "tail -n 0 -F ${RootShell.q(Prefs.log(this))}").redirectErrorStream(true).start()
                tail = p
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    while (running.get()) {
                        val line = r.readLine() ?: break
                        for (item in LogParser.parse(line)) {
                            if (item.domain.isBlank()) continue
                            // Keep a second-chance history even when this domain was notified before.
                            db.recordBlocked(item.domain, item.pattern)
                            if (db.has(item.domain)) continue
                            db.add(item.domain)
                            Notifications.blocked(this, (item.domain.lowercase().hashCode() and 0x7fffffff).coerceAtLeast(1), item.domain, item.pattern)
                        }
                    }
                }
            } catch (e: Exception) { Log.e("DNSCryptMonitor", "Monitor loop error", e) }
            finally { try { tail?.destroyForcibly() } catch (_: Exception) {}; tail = null }
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
        }
    }
    override fun onDestroy() { running.set(false); try { tail?.destroyForcibly() } catch (_: Exception) {}; worker?.interrupt(); if (::db.isInitialized) db.close(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
