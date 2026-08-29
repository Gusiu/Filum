package pl.wspa.filum.protocol

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Odczyt ładunku parowania z kodu QR albo z wklejonego ciągu (§3.3 protokołu).
 *
 * Ładunek niesie odcisk klucza serwera, więc od chwili sparowania adres przestaje być
 * tożsamością (ADR-006). To dlatego parowanie musi przejść dokładnie ten jeden raz —
 * potem telefon rozpoznaje serwer niezależnie od tego, którą trasą go zastanie.
 */
object PairingCode {

    const val PREFIX = "filum1:"

    /** Długość odcisku: base64url z SHA-256 bez dopełnienia. */
    private const val PIN_LENGTH = 43

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class Ok(val payload: QrPayload) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(raw: String): Result {
        val trimmed = raw.trim()

        if (!trimmed.startsWith(PREFIX)) {
            return Result.Invalid("Kod nie zaczyna się od \"$PREFIX\".")
        }

        val decoded = try {
            String(decodeBase64Url(trimmed.removePrefix(PREFIX)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid("Uszkodzone kodowanie kodu parowania.")
        }

        val payload = try {
            json.decodeFromString<QrPayload>(decoded)
        } catch (e: Exception) {
            return Result.Invalid("Kod parowania ma nieznaną strukturę.")
        }

        return validate(payload)
    }

    private fun validate(payload: QrPayload): Result = when {
        payload.key.length != PIN_LENGTH ->
            Result.Invalid("Odcisk klucza serwera ma ${payload.key.length} znaków zamiast $PIN_LENGTH.")

        payload.token.isBlank() ->
            Result.Invalid("Brak tokenu parowania.")

        payload.port !in 1..65_535 ->
            Result.Invalid("Port ${payload.port} jest poza zakresem.")

        payload.addresses.isEmpty() ->
            Result.Invalid("Kod nie zawiera żadnego adresu serwera.")

        else -> Result.Ok(payload)
    }

    /**
     * Dopełnienie dokładamy sami, bo protokół używa base64url bez niego, a dekoder
     * z biblioteki standardowej bywa co do tego wybredny.
     */
    private fun decodeBase64Url(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            0 -> value
            else -> throw IllegalArgumentException("niepoprawna długość base64url")
        }
        return Base64.getUrlDecoder().decode(padded)
    }
}
