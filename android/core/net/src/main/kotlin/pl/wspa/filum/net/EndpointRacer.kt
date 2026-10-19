package pl.wspa.filum.net

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import pl.wspa.filum.protocol.PingResponse
import pl.wspa.filum.protocol.Protocol

/** A route that did not answer, with the reason - it goes into the application's journal. */
data class EndpointFailure(val endpoint: ServerEndpoint, val reason: String)

sealed interface RaceOutcome {

    data class Reached(
        val endpoint: ServerEndpoint,
        val ping: PingResponse,
        val elapsedMillis: Long,
    ) : RaceOutcome

    data class AllFailed(val failures: List<EndpointFailure>) : RaceOutcome {
        /** One entry per route, ready for the journal: the address and why it failed. */
        fun reasons(): List<String> = failures.map { "${it.endpoint}: ${it.reason}" }
    }

    data object NoCandidates : RaceOutcome
}

/** The outcome of looking for a route before pairing - see [EndpointRacer.firstAnswering]. */
sealed interface Reachability {

    data class Answered(val endpoint: ServerEndpoint, val elapsedMillis: Long) : Reachability

    data class Silent(val failures: List<EndpointFailure>) : Reachability {
        /** One entry per route, ready for a message: the address and why it failed. */
        fun reasons(): List<String> = failures.map { "${it.endpoint}: ${it.reason}" }
    }
}

/**
 * The route race (protocol §4.2, ADR-006).
 *
 * Instead of asking one address after another whether the server is there, we ask **all of
 * them at once** and take the first valid answer. At home the local network wins as the
 * fastest, away from home the overlay does, and after a router change a dead address costs
 * only the timeout - in parallel with the rest, so it delays nothing.
 *
 * The racer knows nothing of OkHttp: it is handed a probe function. This is not abstraction
 * for its own sake - local and overlay routes need **different** HTTP clients (binding the
 * socket to a specific network, §4.3), and choosing the client belongs to the layer above.
 */
class EndpointRacer(
    private val probe: suspend (ServerEndpoint) -> ApiResult<PingResponse>,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /** The fastest route to a server that speaks our protocol version. */
    suspend fun fastest(candidates: List<ServerEndpoint>): RaceOutcome {
        val unique = candidates.distinct()
        if (unique.isEmpty()) return RaceOutcome.NoCandidates

        val (winner, failures) = race(unique, ::rejection)
        val result = winner?.result

        // rejection() accepts only Ok, so a winner always carries a ping.
        return if (winner != null && result is ApiResult.Ok) {
            RaceOutcome.Reached(winner.endpoint, result.value, winner.elapsedMillis)
        } else {
            RaceOutcome.AllFailed(failures)
        }
    }

    /**
     * The fastest route on which the server answers at all - for pairing (§3.3).
     *
     * Before pairing the ordinary race cannot work: ping needs a client certificate the
     * phone does not have yet. It does not need one to prove the route, though. The TLS
     * handshake already checks the server's key against the pairing code, so any HTTP
     * answer - "401, not paired" included - comes from the right server over a working
     * route. The single-use token can then go to that route alone.
     */
    suspend fun firstAnswering(candidates: List<ServerEndpoint>): Reachability {
        val (winner, failures) = race(candidates.distinct()) { result ->
            if (result is ApiResult.Unreachable) unreachable(result) else null
        }

        return winner?.let { Reachability.Answered(it.endpoint, it.elapsedMillis) }
            ?: Reachability.Silent(failures)
    }

    /**
     * Probes every route at once and returns the first result [judge] accepts - it returns
     * null for an accepted result and the reason otherwise - with the reasons of the routes
     * that lost before it.
     */
    private suspend fun race(
        candidates: List<ServerEndpoint>,
        judge: (ApiResult<PingResponse>) -> String?,
    ): Pair<Winner?, List<EndpointFailure>> {
        val started = nanoTime()
        val failures = mutableListOf<EndpointFailure>()

        // Kept outside the timeout block on purpose. Leaving coroutineScope waits for the
        // cancelled losers, and a probe blocked in a connect to a dead address may notice
        // cancellation only after the deadline. That delay must not turn a server that
        // has already answered into a lost race.
        var winner: Winner? = null

        withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val pending: MutableList<Pair<ServerEndpoint, Deferred<ApiResult<PingResponse>>>> =
                    candidates.map { it to async { probe(it) } }.toMutableList()

                while (pending.isNotEmpty() && winner == null) {
                    val (index, result) = select {
                        pending.forEachIndexed { i, (_, deferred) ->
                            deferred.onAwait { i to it }
                        }
                    }
                    val (endpoint, _) = pending.removeAt(index)

                    when (val reason = judge(result)) {
                        null -> winner = Winner(endpoint, result, (nanoTime() - started) / 1_000_000)
                        else -> failures += EndpointFailure(endpoint, reason)
                    }
                }

                // We have a winner, so the remaining probes are of no further interest.
                pending.forEach { (_, deferred) -> deferred.cancel() }
            }
        }

        return if (winner != null) winner to failures else null to failures + timedOut(candidates, failures)
    }

    private fun timedOut(
        candidates: List<ServerEndpoint>,
        failures: List<EndpointFailure>,
    ): List<EndpointFailure> {
        val answered = failures.map { it.endpoint }.toSet()
        return candidates.filterNot { it in answered }
            .map { EndpointFailure(it, "No answer within $timeoutMillis ms.") }
    }

    /** Why a ping result does not make a route usable, or null when it does. */
    private fun rejection(result: ApiResult<PingResponse>): String? = when (result) {
        is ApiResult.Ok -> {
            val ping = result.value
            if (ping.protocol == Protocol.VERSION) {
                null
            } else {
                // The route works, but at the other end is a Filum version we do not
                // understand. Another address may still reach the right instance, so we
                // treat this as the route failing, not the whole race.
                "The server speaks protocol ${ping.protocol}, we support ${Protocol.VERSION}."
            }
        }

        is ApiResult.Failure -> "${result.status}: ${result.code}"

        is ApiResult.Unreachable -> unreachable(result)
    }

    // The exception type is kept: OkHttp's messages are often a single word such as
    // "timeout", and the type tells which layer gave up.
    private fun unreachable(result: ApiResult.Unreachable): String =
        "${result.cause::class.java.simpleName}: ${result.message}"

    private class Winner(
        val endpoint: ServerEndpoint,
        val result: ApiResult<PingResponse>,
        val elapsedMillis: Long,
    )

    companion object {
        /** §4.2: two seconds suffice on a local network and do not make you wait from outside. */
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}
