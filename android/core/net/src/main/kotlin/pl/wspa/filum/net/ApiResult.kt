package pl.wspa.filum.net

import pl.wspa.filum.protocol.ErrorResponse
import pl.wspa.filum.protocol.FailureKind
import pl.wspa.filum.protocol.HttpStatus
import java.io.IOException

/**
 * The result of calling a resource. We deliberately do not use exceptions to signal error
 * responses: `409 offset_mismatch` is a **normal** part of resuming a transfer rather than
 * a failure, and it has to be handled, not caught somewhere above along with everything else.
 */
sealed interface ApiResult<out T> {

    data class Ok<T>(val value: T) : ApiResult<T>

    /** The server answered, but with an error code. */
    data class Failure(
        val status: Int,
        val error: ErrorResponse?,
        val kind: FailureKind,
    ) : ApiResult<Nothing> {

        val code: String get() = error?.error ?: "http_$status"

        val message: String get() = error?.message?.takeIf { it.isNotBlank() } ?: "The server answered $status."

        /** The offset the server expects the resume to start from (§5.3). */
        val expectedOffset: Long? get() = error?.expectedOffset
    }

    /** No conversation happened at all - a dropped connection, a timeout, a TLS error. */
    data class Unreachable(val cause: IOException) : ApiResult<Nothing> {
        val message: String get() = cause.message ?: cause::class.java.simpleName
    }

    val isOk: Boolean get() = this is Ok

    companion object {
        fun failure(status: Int, error: ErrorResponse?): Failure =
            Failure(status, error, HttpStatus.classify(status))
    }
}

/** A value or null - for places where the error has already been handled. */
fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value

inline fun <T> ApiResult<T>.onOk(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) action(value)
    return this
}
