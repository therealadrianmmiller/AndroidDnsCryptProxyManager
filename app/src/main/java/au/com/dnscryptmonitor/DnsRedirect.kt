package au.com.dnscryptmonitor

/**
 * The DNS redirect rules (all outbound TCP/UDP 53 except to [EXCLUDE_IP] -> local dnscrypt-proxy).
 * Pause removes exactly these two rules; resume adds them back. Nothing is flushed, so every other
 * NAT rule on the device is left alone. Both operations are idempotent (check with -C first).
 */
object DnsRedirect {
    const val EXCLUDE_IP = "1.1.1.1"
    const val LOCAL_DNS = "127.0.0.1:5354"

    private fun rule(proto: String) = "OUTPUT -p $proto ! -d $EXCLUDE_IP --dport 53 -j DNAT --to-destination $LOCAL_DNS"
    private fun has(proto: String) = "iptables -t nat -C ${rule(proto)} >/dev/null 2>&1"
    private fun add(proto: String) = "iptables -t nat -A ${rule(proto)}"
    private fun del(proto: String) = "iptables -t nat -D ${rule(proto)}"

    /** Exit code 0 when at least one of the two rules is present. */
    val STATUS_CMD = "{ ${has("tcp")} || ${has("udp")}; }"

    /** Removes every copy of both rules; succeeds only if neither remains. */
    val PAUSE_CMD =
        "while ${has("tcp")}; do ${del("tcp")} || break; done; " +
        "while ${has("udp")}; do ${del("udp")} || break; done; " +
        "! ${has("tcp")} && ! ${has("udp")}"

    /** Adds each rule only if it is missing. */
    val RESUME_CMD = "( ${has("tcp")} || ${add("tcp")} ) && ( ${has("udp")} || ${add("udp")} )"

    fun isActive(): Boolean = RootShell.run(STATUS_CMD, 20000).code == 0
}
