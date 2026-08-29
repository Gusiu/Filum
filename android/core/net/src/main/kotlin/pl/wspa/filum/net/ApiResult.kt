package pl.wspa.filum.net

import pl.wspa.filum.protocol.ErrorResponse
import pl.wspa.filum.protocol.FailureKind
import pl.wspa.filum.protocol.HttpStatus
import java.io.IOException

/**
 * Wynik wywołania zasobu. Świadomie nie używamy wyjątków do sygnalizowania odpowiedzi
 * błędnych: `409 offset_mismatch` jest **normalnym** elementem wznawiania transferu,
 * a nie awarią, i musi być obsłużony, a nie złapany gdzieś wyżej razem z resztą.
 */
sealed interface ApiResult<out T> {

    data class Ok<T>(val value: T) : ApiResult<T>

    /** Serwer odpowiedział, ale kodem błędu. */
    data class Failure(
        val status: Int,
        val error: ErrorResponse?,
        val kind: FailureKind,
    ) : ApiResult<Nothing> {

        val code: String get() = error?.error ?: "http_$status"

        val message: String get() = error?.message?.takeIf { it.isNotBlank() } ?: "Serwer odpowiedział $status."

        /** Przesunięcie, od którego serwer oczekuje wznowienia (§5.3). */
        val expectedOffset: Long? get() = error?.expectedOffset
    }

    /** Nie udało się w ogóle porozmawiać — zerwane połączenie, limit czasu, błąd TLS. */
    data class Unreachable(val cause: IOException) : ApiResult<Nothing> {
        val message: String get() = cause.message ?: cause::class.java.simpleName
    }

    val isOk: Boolean get() = this is Ok

    companion object {
        fun failure(status: Int, error: ErrorResponse?): Failure =
            Failure(status, error, HttpStatus.classify(status))
    }
}

/** Wartość albo null — do miejsc, w których obsługa błędu nastąpiła już wcześniej. */
fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value

inline fun <T> ApiResult<T>.onOk(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) action(value)
    return this
}
