package pl.wspa.filum.app

import pl.wspa.filum.data.EndpointEntity
import pl.wspa.filum.net.ServerEndpoint

/** Keeping the stored route set current (protocol §4.4). */
object RouteBook {

    /**
     * The rows to save for routes found now - from the ping response, the pairing code or
     * mDNS - merged with the stored ones.
     *
     * A known route keeps its history, when it last worked and how fast, and only takes
     * the new kind. Saving fresh rows instead wiped that history on every cycle, so the
     * route order never learned anything.
     */
    fun merge(stored: List<EndpointEntity>, found: List<ServerEndpoint>): List<EndpointEntity> {
        val known = stored.associateBy { it.host to it.port }

        return found.distinctBy { it.host to it.port }.map { route ->
            known[route.host to route.port]?.copy(kind = route.kind.name)
                ?: EndpointEntity(route.host, route.port, route.kind.name)
        }
    }
}
