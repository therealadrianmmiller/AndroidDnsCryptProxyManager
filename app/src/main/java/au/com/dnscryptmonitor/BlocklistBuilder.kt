package au.com.dnscryptmonitor

import android.content.Context
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter

/**
 * Builds blocked-names.txt from the saved selection.
 * Flow: back up current list -> download each source to /data/local/tmp -> parse -> sort -u -> (optionally drop
 * subdomains of listed parents) -> write to the DNSCrypt directory. Memory use stays flat: files are streamed and
 * the sort is done by the system `sort` as root. Any failure restores the backup.
 */
object BlocklistBuilder {
    private const val PREFERRED_TMP = "/data/local/tmp/dnscrypt-build"

    class Result(val ok: Boolean, val summary: String)

    // Same parsing rules as generate-domains-blocklist.py
    private val rxComment = Regex("^(#|$)")
    private val rxInline = Regex("""\s*#\s*[a-z0-9-].*$""")
    private val rxs = listOf(
        Regex("""^@*\|\|([a-z0-9][a-z0-9.-]*[.][a-z]{2,})\^?(\$(popup|third-party))?$"""),
        Regex("""^([a-z0-9][a-z0-9.-]*[.][a-z]{2,})$"""),
        Regex("""^[*][.]([a-z0-9][a-z0-9.-]*[.][a-z]{2,})$"""),
        Regex("""^[0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}\s+([a-z0-9][a-z0-9.-]*[.][a-z]{2,})$"""),
        Regex("""^"[^"]+","([a-z0-9][a-z0-9.-]*[.][a-z]{2,})","""),
        Regex("""^([a-z0-9][a-z0-9.-]*[.][a-z]{2,}),.+,[0-9: /-]+,"""),
        Regex("""^address=/([a-z0-9][a-z0-9.-]*[.][a-z]{2,})/.""")
    )

    // dnscrypt-proxy name patterns (not full regex): * ? [] wildcards, or =exact.name
    private val rxPattern = Regex("""^=?[a-z0-9._*?\[\]-]+$""")

    /** A local-additions line that is a dnscrypt-proxy pattern (e.g. "ads.*"), cleaned up, or null. */
    private fun localPattern(raw: String): String? {
        val t = rxInline.replace(raw.trim().lowercase(), "").trim()
        val hasWildcard = t.startsWith("=") || t.any { it == '*' || it == '?' || it == '[' }
        return if (rxPattern.matches(t) && hasWildcard && t.any { it.isLetterOrDigit() }) t else null
    }

    fun parseLine(raw: String): String? {
        val lower = raw.trim().lowercase()
        if (rxComment.containsMatchIn(lower)) return null
        val line = rxInline.replace(lower, "")
        for (rx in rxs) {
            val m = rx.find(line)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    // "ads.example.com" -> "com.example.ads." (trailing dot keeps a parent directly before its subdomains when sorted)
    private fun toKey(domain: String) = domain.split('.').reversed().joinToString(".") + "."
    private fun toDomain(key: String) = key.removeSuffix(".").split('.').reversed().joinToString(".")

    private fun sh(cmd: String, timeoutMs: Long = 20000L) = RootShell.run(cmd, timeoutMs)

    /**
     * First temp folder root can create and write to: /data/local/tmp is preferred, the app's own cache dir is the
     * fallback. Returns (path, "") on success or (null, what went wrong) so the real error is shown to the user.
     */
    private fun pickTmp(c: Context, log: (String) -> Unit): Pair<String?, String> {
        val problems = StringBuilder()
        for (dir in listOf(PREFERRED_TMP, c.cacheDir.absolutePath + "/dnscrypt-build")) {
            val q = RootShell.q(dir)
            val r = sh("rm -rf $q; mkdir -p $q && touch $q/.w && rm -f $q/.w")
            if (r.code == 0) {
                if (dir != PREFERRED_TMP) log("Using $dir (/data/local/tmp was not usable)")
                return Pair(dir, "")
            }
            problems.append("$dir (code ${r.code}): ${r.output.trim()}\n")
        }
        return Pair(null, problems.toString().trim())
    }

    private fun downloadTo(url: String, dest: String, timeoutMs: Int) {
        val conn = BlocklistSources.open(url, timeoutMs)
        var proc: Process? = null
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val p = ProcessBuilder("su", "-c", "cat > ${RootShell.q(dest)}").redirectErrorStream(true).start()
            proc = p
            conn.inputStream.use { input -> p.outputStream.use { out -> input.copyTo(out, 64 * 1024) } }
            if (p.waitFor() != 0) throw IOException("could not write temporary file")
        } finally {
            conn.disconnect()
            proc?.destroy()
        }
    }

    fun build(c: Context, log: (String) -> Unit): Result {
        val urls = BlocklistPrefs.sources(c).filter { it.selected }.map { it.url }
        if (urls.isEmpty()) return Result(false, "No sources selected.")
        val ignoreFailures = BlocklistPrefs.ignoreFailures(c)
        val timeoutMs = BlocklistPrefs.timeoutSec(c).coerceIn(1, 600) * 1000
        val collapse = BlocklistPrefs.collapse(c)

        val dest = Prefs.block(c)
        val bak = dest.substringBeforeLast('/') + "/blocked-names.bak"
        val qDest = RootShell.q(dest)
        val qBak = RootShell.q(bak)

        log("Checking root access")
        val idr = sh("id")
        if (idr.code != 0 || !idr.output.contains("uid=0")) {
            return Result(
                false,
                "Root access is not available (code ${idr.code}): ${idr.output.trim()}. " +
                    "In the KernelSU manager, open Superuser and allow DNSCrypt Monitor, then try again. Nothing was changed."
            )
        }

        log("Backing up current blocked-names.txt")
        val hadFile = sh("[ -f $qDest ]").code == 0
        if (hadFile) {
            val r = sh("mv -f $qDest $qBak")
            if (r.code != 0) return Result(false, "Backup failed: ${r.output}")
        }
        var tmp = ""
        fun abort(msg: String): Result {
            if (hadFile) sh("rm -f $qDest; mv -f $qBak $qDest")
            if (tmp.isNotEmpty()) sh("rm -rf ${RootShell.q(tmp)}")
            return Result(false, "$msg Previous list restored.")
        }

        try {
            val (picked, why) = pickTmp(c, log)
            if (picked == null) return abort("Could not create a temporary folder. $why")
            tmp = picked

            val files = ArrayList<String>()
            for ((i, url) in urls.withIndex()) {
                val name = url.substringAfterLast('/')
                log("Downloading ${i + 1}/${urls.size}: $name")
                val path = "$tmp/src_$i.txt"
                try {
                    downloadTo(url, path, timeoutMs)
                    files.add(path)
                } catch (e: Exception) {
                    log("FAILED $name: ${e.message}")
                    if (!ignoreFailures) return abort("Download of $name failed: ${e.message}.")
                }
            }
            if (files.isEmpty()) return abort("No source could be downloaded.")

            log("Parsing ${files.size} file(s)")
            val keys = "$tmp/keys.txt"
            var parsed = 0L
            val kp = ProcessBuilder("su", "-c", "cat > ${RootShell.q(keys)}").redirectErrorStream(true).start()
            val kw = BufferedWriter(OutputStreamWriter(kp.outputStream, Charsets.UTF_8), 1 shl 16)
            for (f in files) {
                val rp = ProcessBuilder("su", "-c", "cat ${RootShell.q(f)}").redirectErrorStream(true).start()
                rp.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val d = parseLine(line) ?: continue
                        kw.write(toKey(d)); kw.write("\n")
                        parsed++
                    }
                }
                rp.waitFor()
            }
            // Local additions are always included, whatever happened with the downloads.
            var localParsed = 0L
            var localSkipped = 0L
            val localPatterns = java.util.TreeSet<String>()
            val localPath = LocalAdditions.path(c)
            if (sh("[ -f ${RootShell.q(localPath)} ]").code == 0) {
                val lp = ProcessBuilder("su", "-c", "cat ${RootShell.q(localPath)}").redirectErrorStream(true).start()
                lp.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val t = line.trim()
                        if (t.isEmpty() || t.startsWith("#")) continue
                        val d = parseLine(line)
                        if (d != null) {
                            kw.write(toKey(d)); kw.write("\n")
                            parsed++; localParsed++
                        } else {
                            // Patterns like "ads.*" can't be sorted/merged by domain, so they are kept as written.
                            val pat = localPattern(line)
                            if (pat != null) localPatterns.add(pat) else localSkipped++
                        }
                    }
                }
                lp.waitFor()
                log("Local additions: $localParsed domain(s), ${localPatterns.size} pattern(s)" + if (localSkipped > 0) ", $localSkipped line(s) not recognised and skipped" else "")
            }
            kw.close()
            kp.waitFor()
            if (parsed == 0L) return abort("No domains could be parsed from the downloaded files.")

            log("Sorting $parsed entries")
            val sorted = "$tmp/sorted.txt"
            val s = sh("LC_ALL=C sort -u ${RootShell.q(keys)} -o ${RootShell.q(sorted)}", 600000)
            if (s.code != 0) return abort("Sort failed: ${s.output}.")
            sh("rm -f ${RootShell.q(keys)}")

            log(if (collapse) "Removing duplicates and covered subdomains" else "Writing list")
            val result = "$tmp/result.txt"
            var kept = 0L
            val wp = ProcessBuilder("su", "-c", "cat > ${RootShell.q(result)}").redirectErrorStream(true).start()
            val ww = BufferedWriter(OutputStreamWriter(wp.outputStream, Charsets.UTF_8), 1 shl 16)
            val sp = ProcessBuilder("su", "-c", "cat ${RootShell.q(sorted)}").redirectErrorStream(true).start()
            for (pt in localPatterns) { ww.write(pt); ww.write("\n") }
            var last: String? = null
            sp.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (key in lines) {
                    if (key.isEmpty()) continue
                    val l = last
                    if (collapse && l != null && key.startsWith(l)) continue
                    last = key
                    kept++
                    ww.write(toDomain(key)); ww.write("\n")
                }
            }
            sp.waitFor()
            ww.close()
            wp.waitFor()
            if (kept == 0L && localPatterns.isEmpty()) return abort("Result was empty.")

            log("Installing new blocked-names.txt")
            val w = sh("cat ${RootShell.q(result)} > $qDest", 120000)
            if (w.code != 0 || sh("[ -s $qDest ]").code != 0) return abort("Could not write $dest: ${w.output}.")

            sh("rm -rf ${RootShell.q(tmp)}")
            val dupes = parsed - kept
            return Result(
                true,
                "Built $kept domains from ${files.size}/${urls.size} sources plus $localParsed local domain(s) and ${localPatterns.size} pattern(s) ($parsed parsed, $dupes duplicates/covered removed)." +
                    if (hadFile) " Old list saved as blocked-names.bak." else ""
            )
        } catch (e: Exception) {
            return abort("Build failed: ${e.message}.")
        }
    }
}
