package pl.wspa.filum.net

/**
 * Where this route came from. The kind decides two things: whether the socket has to be
 * bound to a specific network (protocol §4.3) and which transfer policy applies
 * once it has won (architecture §3.6).
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
     * `Network` object so they work despite an active VPN - otherwise the packet would
     * enter the tunnel and 192.168.x.x would become unreachable.
     */
    val isLocal: Boolean get() = this == MDNS || this == LAN

    companion object {
        /**
         * The kind of an address the server reported about itself, judged by its range.
         *
         * The pairing code and the ping response list every interface of the computer
         * together, overlay addresses among them. Taking them all for local addresses bound
         * the overlay one to the Wi-Fi network too, where it can never answer.
         */
        fun of(host: String): EndpointKind {
            val ipv4 = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { part -> part in 0..255 } }
            if (ipv4.size == 4 && host.count { it == '.' } == 3) {
                val (a, b) = ipv4
                return when {
                    // 100.64.0.0/10 - shared address space, used by Meshnet and Tailscale.
                    a == 100 && b in 64..127 -> OVERLAY
                    a == 10 -> LAN
                    a == 172 && b in 16..31 -> LAN
                    a == 192 && b == 168 -> LAN
                    a == 169 && b == 254 -> LAN
                    else -> MANUAL
                }
            }

            val ipv6 = host.lowercase()
            if (ipv6.contains(':')) {
                return when {
                    // Tailscale takes its IPv6 addresses from one unique local prefix.
                    ipv6.startsWith("fd7a:115c:a1e0:") -> OVERLAY
                    // fe80::/10 link-local and fc00::/7 unique local addresses.
                    ipv6.take(3) in setOf("fe8", "fe9", "fea", "feb") -> LAN
                    ipv6.startsWith("fc") || ipv6.startsWith("fd") -> LAN
                    else -> MANUAL
                }
            }

            // A name - a DDNS address, say - says nothing about where it leads.
            return MANUAL
        }
    }
}

/**
 * One candidate route to the server. The client stores no "server address",
 * only a set of such candidates (ADR-006) - the identity is the key fingerprint, not an address.
 */
data class ServerEndpoint(
    val host: String,
    val port: Int,
    val kind: EndpointKind,
    /**
     * Whether the connection goes over a socket bound to the Wi-Fi network rather than over
     * the default route (§4.3). It is a property of the attempt, not of the address: the
     * same local address is raced both ways, and only the race tells which one works.
     */
    val boundToWifi: Boolean = false,
) {
    val authority: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    val baseUrl: String get() = "https://$authority"

    override fun toString(): String =
        "$authority (${kind.name.lowercase()}${if (boundToWifi) ", wifi-bound" else ""})"

    /** The same address with the kind its range implies - see [EndpointKind.of]. */
    fun classified(): ServerEndpoint = copy(kind = EndpointKind.of(host))

    companion object {
        /**
         * Parses `host:port` or `[ipv6]:port` from the ping response (§4.4) or
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
            // requires brackets when a port follows an IPv6 address - without this rule
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
