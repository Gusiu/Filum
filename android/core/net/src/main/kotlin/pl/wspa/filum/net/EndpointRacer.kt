package pl.wspa.filum.net

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import pl.wspa.filum.protocol.PingResponse
import pl.wspa.filum.protocol.Protocol

/** Trasa, która nie odpowiedziała, wraz z powodem — trafia do dziennika w aplikacji. */
data class EndpointFailure(val endpoint: ServerEndpoint, val reason: String)

sealed interface RaceOutcome {

    data class Reached(
        val endpoint: ServerEndpoint,
        val ping: PingResponse,
        val elapsedMillis: Long,
    ) : RaceOutcome

    data class AllFailed(val failures: List<EndpointFailure>) : RaceOutcome

    data object NoCandidates : RaceOutcome
}

/**
 * Wyścig tras (§4.2 protokołu, ADR-006).
 *
 * Zamiast pytać kolejno „czy serwer jest pod tym adresem", pytamy **wszystkich naraz**
 * i bierzemy pierwszą poprawną odpowiedź. Dzięki temu w domu wygrywa sieć lokalna jako
 * najszybsza, poza domem nakładkowa, a po zmianie routera martwy adres kosztuje tylko
 * tyle, ile trwa limit czasu — i to równolegle z resztą, więc nic nie opóźnia.
 *
 * Racer nie zna OkHttpa: dostaje gotową funkcję sondującą. To nie jest abstrakcja dla
 * samej abstrakcji — trasy lokalne i nakładkowe wymagają **różnych** klientów HTTP
 * (przypięcie gniazda do konkretnej sieci, §4.3), a wybór klienta należy do warstwy wyżej.
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

        val outcome = withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val pending: MutableList<Pair<ServerEndpoint, Deferred<ApiResult<PingResponse>>>> =
                    unique.map { it to async { probe(it) } }.toMutableList()

                var winner: RaceOutcome.Reached? = null

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

                // Zwycięzca jest, więc reszta sondowań jest już nieinteresująca.
                pending.forEach { (_, deferred) -> deferred.cancel() }
                winner
            }
        }

        return outcome ?: RaceOutcome.AllFailed(failures + timedOut(unique, failures))
    }

    private fun timedOut(
        candidates: List<ServerEndpoint>,
        failures: List<EndpointFailure>,
    ): List<EndpointFailure> {
        val answered = failures.map { it.endpoint }.toSet()
        return candidates.filterNot { it in answered }
            .map { EndpointFailure(it, "Brak odpowiedzi w ciągu $timeoutMillis ms.") }
    }

    private fun evaluate(endpoint: ServerEndpoint, result: ApiResult<PingResponse>): Evaluation =
        when (result) {
            is ApiResult.Ok -> {
                val ping = result.value
                if (ping.protocol == Protocol.VERSION) {
                    Evaluation.Usable(ping)
                } else {
                    // Trasa działa, ale po drugiej stronie jest Filum w wersji, której
                    // nie rozumiemy. Innym adresem możemy trafić na właściwą instancję,
                    // więc traktujemy to jak porażkę trasy, a nie całego wyścigu.
                    Evaluation.Rejected(
                        "Serwer mówi protokołem ${ping.protocol}, obsługujemy ${Protocol.VERSION}."
                    )
                }
            }

            is ApiResult.Failure -> Evaluation.Rejected("${result.status}: ${result.code}")

            is ApiResult.Unreachable -> Evaluation.Rejected(result.message)
        }

    private sealed interface Evaluation {
        data class Usable(val ping: PingResponse) : Evaluation
        data class Rejected(val reason: String) : Evaluation
    }

    companion object {
        /** §4.2: dwie sekundy wystarczą w sieci lokalnej i nie każą czekać poza domem. */
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}
