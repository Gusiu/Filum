package pl.wspa.filum.net

/**
 * Where this route came from. The kind decides two things: whether the socket has to be
 * bound to a specific network (protocol §4.3) and which transfer policy applies
 * po jej wygraniu (§3.6 architektury).
 */
enum class EndpointKind {
    /** Found over mDNS on the current local network. */
    MDNS,

    /** The last working local network address, from cache. */
    LAN,

    /** An overlay network: Meshnet, Tailscale, WireGuard. Works from outside the home. */
    OVERLAY,

    /** Entered by hand or taken from the pairing code, before we know what it is. */
    MANUAL;

    /**
     * Whether the route goes over the local network. Such sockets are bound to a specific
     * `Network` object so they work despite an active VPN — otherwise the packet would
     * enter the tunnel and 192.168.x.x would become unreachable.
     */
    val isLocal: Boolean get() = this == MDNS || this == LAN
}

/**
 * One candidate route to the server. The client stores no "server address",
 * only a set of such candidates (ADR-006) — the identity is the key fingerprint, not an address.
 */
data class ServerEndpoint(
    val host: String,
    val port: Int,
    val kind: EndpointKind,
) {
    val authority: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    val baseUrl: String get() = "https://$authority"

    override fun toString(): String = "$authority (${kind.name.lowercase()})"

    companion object {
        /**
         * Rozbiera zapis `host:port` albo `[ipv6]:port` z odpowiedzi na ping (§4.4) lub
         * from the pairing code. It returns null rather than throwing, because the address list
         * comes off the network and one broken entry must not wreck the whole route refresh.
         */
        fun parse(raw: String, kind: EndpointKind, defaultPort: Int = 8765): ServerEndpoint? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close < 0) return null
                val host = trimmed.substring(1, close)
                val port = trimmed.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return if (host.isNotEmpty() && port in 1..65_535) ServerEndpoint(host, port, kind) else null
            }

            // More than one colon without brackets means a bare IPv6 address. RFC 3986
            // requires brackets when a port follows an IPv6 address — without this rule
            // the last group of "fe80::1" would look like a port number.
            if (trimmed.count { it == ':' } > 1) {
                return ServerEndpoint(trimmed, defaultPort, kind)
            }

            val separator = trimmed.lastIndexOf(':')
            val host: String
            val port: Int

            if (separator < 0) {
                host = trimmed
                port = defaultPort
            } else {
                host = trimmed.substring(0, separator)
                port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
            }

            return if (host.isNotEmpty() && port in 1..65_535) ServerEndpoint(host, port, kind) else null
        }
    }
}
