package pl.wspa.filum.protocol

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Parsing the pairing payload from a QR code or a pasted string (protocol §3.3).
 *
 * The payload carries the server's key fingerprint, so from the moment of pairing the
 * address stops being an identity (ADR-006). That is why pairing has to succeed exactly
 * this once - afterwards the phone recognises the server by whatever route it finds it.
 */
object PairingCode {

    const val PREFIX = "filum1:"

    /** Fingerprint length: base64url of SHA-256 without padding. */
    private const val PIN_LENGTH = 43

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class Ok(val payload: QrPayload) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(raw: String): Result {
        val trimmed = raw.trim()

        if (!trimmed.startsWith(PREFIX)) {
            return Result.Invalid("The code does not start with \"$PREFIX\".")
        }

        val decoded = try {
            String(decodeBase64Url(trimmed.removePrefix(PREFIX)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid("Broken encoding in the pairing code.")
        }

        val payload = try {
            json.decodeFromString<QrPayload>(decoded)
        } catch (e: Exception) {
            return Result.Invalid("The pairing code has an unrecognised structure.")
        }

        return validate(payload)
    }

    private fun validate(payload: QrPayload): Result = when {
        payload.key.length != PIN_LENGTH ->
            Result.Invalid("The server key fingerprint is ${payload.key.length} characters instead of $PIN_LENGTH.")

        payload.token.isBlank() ->
            Result.Invalid("No pairing token.")

        payload.port !in 1..65_535 ->
            Result.Invalid("Port ${payload.port} is out of range.")

        payload.addresses.isEmpty() ->
            Result.Invalid("The code contains no server address.")

        else -> Result.Ok(payload)
    }

    /**
     * We add the padding ourselves, because the protocol uses base64url without it and the decoder
     * z biblioteki standardowej bywa co do tego wybredny.
     */
    private fun decodeBase64Url(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            0 -> value
            else -> throw IllegalArgumentException("invalid base64url length")
        }
        return Base64.getUrlDecoder().decode(padded)
    }
}
