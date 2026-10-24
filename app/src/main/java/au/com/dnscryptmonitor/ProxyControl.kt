package au.com.dnscryptmonitor

/** Single source of truth for how the proxy is restarted. Every restart in the app goes through here. */
object ProxyControl {
    const val BINARY_PATH = "/system/bin/dnscrypt-proxy"
    const val CONFIG_PATH = "/storage/emulated/0/dnscrypt-proxy/dnscrypt-proxy.toml"
    const val RESTART_CMD = "pkill -f $BINARY_PATH; $BINARY_PATH -config $CONFIG_PATH > /dev/null 2>&1 &"

    /** Restart command, optionally preceded by [before], which must succeed for the restart to run. */
    fun restartCommand(before: String? = null): String =
        if (before == null) RESTART_CMD else "$before && { $RESTART_CMD }"
}
