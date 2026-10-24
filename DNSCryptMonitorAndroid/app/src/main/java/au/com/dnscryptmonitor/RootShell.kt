package au.com.dnscryptmonitor
import java.io.BufferedReader
import java.io.InputStreamReader
data class ShellResult(val code: Int, val output: String)
object RootShell {
    fun run(command: String, timeoutMs: Long = 15000): ShellResult = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread { try { BufferedReader(InputStreamReader(p.inputStream)).use { r -> while (true) { val line = r.readLine() ?: break; out.append(line).append('\n') } } } catch (_: Exception) {} }
        t.start()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) { p.destroyForcibly(); ShellResult(124, "Command timed out") }
        else { t.join(1000); ShellResult(p.exitValue(), out.toString().trim()) }
    } catch (e: Exception) { ShellResult(127, e.message ?: "Could not execute su") }
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
