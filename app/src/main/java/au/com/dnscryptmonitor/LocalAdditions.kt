package au.com.dnscryptmonitor

import android.content.Context

/** Your own domains, kept in local-additions.txt next to blocked-names.txt and merged into every built blocklist. */
object LocalAdditions {
    const val FILE_NAME = "local-additions.txt"

    const val TEMPLATE =
        "# Local additions: your own names and patterns to block, one per line.\n" +
            "# Always merged into the blocklist when you build it. Lines starting with # are ignored.\n" +
            "# A plain name blocks it and its subdomains:   ads.example.com\n" +
            "# Patterns use dnscrypt-proxy wildcards (* ? []), not full regex:\n" +
            "#   ads.*          any name starting with \"ads.\"\n" +
            "#   *sex*          any name containing sex\n" +
            "#   =example.com   exactly example.com, not its subdomains\n"

    fun path(c: Context): String = Prefs.block(c).substringBeforeLast('/') + "/" + FILE_NAME

    /** File text, "" if the file doesn't exist yet, or null if root couldn't be used. */
    fun read(c: Context): String? {
        val r = RootShell.run("cat ${RootShell.q(path(c))} 2>/dev/null", 20000)
        return if (r.code == 127) null else r.output
    }

    fun write(c: Context, text: String): ShellResult {
        val body = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
        return try {
            val p = ProcessBuilder("su", "-c", "cat > ${RootShell.q(path(c))}").redirectErrorStream(true).start()
            p.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val out = p.inputStream.bufferedReader().readText()
            ShellResult(p.waitFor(), out)
        } catch (e: Exception) {
            ShellResult(127, e.message ?: "write failed")
        }
    }

    fun countDomains(text: String): Int = text.lines().count {
        val t = it.trim()
        t.isNotEmpty() && !t.startsWith("#")
    }
}
