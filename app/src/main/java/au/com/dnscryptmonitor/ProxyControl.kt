package au.com.dnscryptmonitor

/** Single source of truth for how the proxy is restarted. Every restart in the app goes through here. */
object ProxyControl {
    const val BINARY_NAME = "dnscrypt-proxy"
    const val BINARY_PATH = "/system/bin/$BINARY_NAME"
    const val CONFIG_PATH = "/storage/emulated/0/dnscrypt-proxy/dnscrypt-proxy.toml"

    // pkill -x matches the process NAME only. The old "pkill -f <path>" also matched the root shell running
    // this very command (its command line contains the path), so pkill killed its own shell: exit 143 (SIGTERM).
    // sleep gives the old process time to release its port; nohup keeps the new one alive after the shell exits.
    // "< /dev/null" matters too: a background job that still holds stdin can stop su from exiting, so the
    // caller times out and kills it (also reported as 143).
    const val RESTART_CMD =
        "pkill -x $BINARY_NAME; sleep 1; nohup $BINARY_PATH -config $CONFIG_PATH > /dev/null 2>&1 < /dev/null &"

    /** Restart command, optionally preceded by [before], which must succeed for the restart to run. */
    fun restartCommand(before: String? = null): String =
        if (before == null) RESTART_CMD else "$before && { $RESTART_CMD }"
}
