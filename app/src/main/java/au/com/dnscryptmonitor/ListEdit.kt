package au.com.dnscryptmonitor

object ListEdit {
    /**
     * Removes every line exactly equal to [line] from the list at [path], without loading the file into memory.
     * Keeps a single backup at "<path>.bak". Writes in place (cat >) so owner/permissions stay as they were.
     * Codes: 0 ok, 2 invalid entry, 3 backup failed (file untouched), 4 entry not found (file untouched).
     */
    fun removeExact(path: String, line: String): ShellResult {
        if (line.isBlank() || line.contains('\n') || line.contains('\r')) return ShellResult(2, "Invalid entry")
        val f = RootShell.q(path)
        val l = RootShell.q(line)
        val cmd = "( f=$f; t=\"\$f.tmp\"; " +
            "grep -q -x -F -- $l \"\$f\" || exit 4; " +
            "cp \"\$f\" \"\$f.bak\" || exit 3; " +
            "grep -v -x -F -- $l \"\$f\" > \"\$t\"; r=\$?; " +
            "if [ \$r -le 1 ]; then cat \"\$t\" > \"\$f\"; r=0; fi; " +
            "rm -f \"\$t\"; exit \$r )"
        val res = RootShell.run(cmd, 60000)
        return if (res.code == 4) ShellResult(4, "Entry not found in list") else res
    }
}
