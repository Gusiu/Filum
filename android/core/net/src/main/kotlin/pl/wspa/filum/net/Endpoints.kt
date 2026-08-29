package pl.wspa.filum.net

/**
 * Skąd wzięliśmy tę trasę. Rodzaj decyduje o dwóch rzeczach: czy gniazdo trzeba
 * przypiąć do konkretnej sieci (§4.3 protokołu) i jaka polityka transferu obowiązuje
 * po jej wygraniu (§3.6 architektury).
 */
enum class EndpointKind {
    /** Znaleziona przez mDNS w bieżącej sieci lokalnej. */
    MDNS,

    /** Ostatni skuteczny adres w sieci lokalnej, z pamięci podręcznej. */
    LAN,

    /** Sieć nakładkowa: Meshnet, Tailscale, WireGuard. Działa spoza domu. */
    OVERLAY,

    /** Wpisana ręcznie albo pochodząca z kodu parowania, zanim poznamy jej naturę. */
    MANUAL;

    /**
     * Czy trasa prowadzi przez sieć lokalną. Takie gniazda przypinamy do konkretnego
     * obiektu `Network`, żeby działały mimo aktywnego VPN — inaczej pakiet wszedłby
     * w tunel i adres 192.168.x.x stałby się nieosiągalny.
     */
    val isLocal: Boolean get() = this == MDNS || this == LAN
}

/**
 * Jeden kandydat na dotarcie do serwera. Klient nie przechowuje „adresu serwera",
 * tylko zbiór takich kandydatów (ADR-006) — tożsamością jest odcisk klucza, nie adres.
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
         * z kodu parowania. Zwraca null zamiast rzucać, bo lista adresów pochodzi
         * z sieci i jeden zepsuty wpis nie może wywrócić całego odświeżenia tras.
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

            // Więcej niż jeden dwukropek bez nawiasów to goły adres IPv6. RFC 3986
            // wymaga nawiasów, gdy po adresie IPv6 następuje port — bez tej reguły
            // ostatni człon "fe80::1" wyglądałby jak numer portu.
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
