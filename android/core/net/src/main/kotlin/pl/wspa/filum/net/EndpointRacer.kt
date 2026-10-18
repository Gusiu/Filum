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

    suspend fun fastest(candidates: List<ServerEndpoint>): RaceOutcome {
        val unique = candidates.distinct()
        if (unique.isEmpty()) return RaceOutcome.NoCandidates

        val started = nanoTime()
        val failures = mutableListOf<EndpointFailure>()

        // Kept outside the timeout block on purpose. Leaving coroutineScope waits for the
        // cancelled losers, and a probe blocked in a connect to a dead address may notice
        // cancellation only after the deadline. That delay must not turn a server that
        // has already answered into a lost race.
        var winner: RaceOutcome.Reached? = null

        withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val pending: MutableList<Pair<ServerEndpoint, Deferred<ApiResult<PingResponse>>>> =
                    unique.map { it to async { probe(it) } }.toMutableList()

                while (pending.isNotEmpty() && winner == null) {
                    val (index, result) = select {
                        pending.forEachIndexed { i, (_, deferred) ->
                            deferred.onAwait { i to it }
                        }
                    }
                    val (endpoint, _) = pending.removeAt(index)

                    when (val evaluated = evaluate(endpoint, result)) {
                        is Evaluation.Usable -> winner = RaceOutcome.Reached(
                            endpoint = endpoint,
                            ping = evaluated.ping,
                            elapsedMillis = (nanoTime() - started) / 1_000_000,
                        )

                        is Evaluation.Rejected -> failures += EndpointFailure(endpoint, evaluated.reason)
                    }
                }

                // We have a winner, so the remaining probes are of no further interest.
                pending.forEach { (_, deferred) -> deferred.cancel() }
            }
        }

        return winner ?: RaceOutcome.AllFailed(failures + timedOut(unique, failures))
    }

    private fun timedOut(
        candidates: List<ServerEndpoint>,
        failures: List<EndpointFailure>,
    ): List<EndpointFailure> {
        val answered = failures.map { it.endpoint }.toSet()
        return candidates.filterNot { it in answered }
            .map { EndpointFailure(it, "No answer within $timeoutMillis ms.") }
    }

    private fun evaluate(endpoint: ServerEndpoint, result: ApiResult<PingResponse>): Evaluation =
        when (result) {
            is ApiResult.Ok -> {
                val ping = result.value
                if (ping.protocol == Protocol.VERSION) {
                    Evaluation.Usable(ping)
                } else {
                    // The route works, but at the other end is a Filum version we do not
                    // understand. Another address may still reach the right instance, so we
                    // treat this as the route failing, not the whole race.
                    Evaluation.Rejected(
                        "The server speaks protocol ${ping.protocol}, we support ${Protocol.VERSION}."
                    )
                }
            }

            is ApiResult.Failure -> Evaluation.Rejected("${result.status}: ${result.code}")

            // The exception type is kept: OkHttp's messages are often a single word such as
            // "timeout", and the type tells which layer gave up.
            is ApiResult.Unreachable -> Evaluation.Rejected("${result.cause::class.java.simpleName}: ${result.message}")
        }

    private sealed interface Evaluation {
        data class Usable(val ping: PingResponse) : Evaluation
        data class Rejected(val reason: String) : Evaluation
    }

    companion object {
        /** §4.2: two seconds suffice on a local network and do not make you wait from outside. */
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}
