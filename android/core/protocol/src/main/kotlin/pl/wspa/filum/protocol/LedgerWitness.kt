package pl.wspa.filum.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** A head the phone witnessed: the ledger had [entries] lines, the last of them hashing to [digest]. */
data class WitnessedHead(val entries: Long, val digest: String)

/**
 * Checking the server's ledger against the head this phone witnessed last (protocol §9.5).
 *
 * Pure computation over what GET /ledger returned - no Android, no network - so it is tested
 * on the JVM, and through the conformance test against lines the C# server wrote.
 */
object LedgerWitness {

    const val GENESIS = "genesis"

    sealed interface Verdict {
        /** The ledger still holds what was witnessed, and the lines after it chain onto it. */
        data class Extends(val head: WitnessedHead) : Verdict

        /** Something the phone witnessed is no longer there, or the lines do not chain. */
        data class Rewritten(val reason: String) : Verdict
    }

    /** The line to ask for first: the witnessed one itself, so that its digest is checked again. */
    fun startingLine(previous: WitnessedHead?): Long =
        if (previous == null || previous.entries == 0L) 1 else previous.entries

    /**
     * [lines] is the ledger from [startingLine] to its end, gathered from as many pages as it
     * took; [entries] and [head] are what the server stated with the last page.
     */
    fun check(previous: WitnessedHead?, lines: List<String>, entries: Long, head: String): Verdict {
        val witnessed = previous?.takeIf { it.entries > 0 }
        val start = startingLine(previous)

        if (witnessed != null && entries < witnessed.entries) {
            return Verdict.Rewritten("The server reports $entries entries, fewer than the ${witnessed.entries} witnessed.")
        }
        if (entries == 0L) {
            return if (lines.isEmpty() && head == GENESIS) {
                Verdict.Extends(WitnessedHead(0, GENESIS))
            } else {
                Verdict.Rewritten("An empty ledger has no lines and the anchor for its head.")
            }
        }
        if (lines.size.toLong() != entries - start + 1) {
            return Verdict.Rewritten("Expected lines $start to $entries, received ${lines.size}.")
        }

        var previousDigest: String? = null
        for ((index, line) in lines.withIndex()) {
            val number = start + index
            val entry = parse(line) ?: return Verdict.Rewritten("Line $number cannot be read.")
            // A field of the wrong shape is a line that does not read as a ledger line - an
            // answer for the verdict, not an exception that would take the cycle down.
            if ((entry["n"] as? JsonPrimitive)?.longOrNull != number) {
                return Verdict.Rewritten("Line $number carries the number ${entry["n"]}.")
            }

            val pointer = (entry["prev"] as? JsonPrimitive)?.contentOrNull
            val digest = digestOf(line)
            when {
                index == 0 && witnessed != null && digest != witnessed.digest ->
                    return Verdict.Rewritten("Line $number is not the line witnessed.")

                index == 0 && witnessed == null && pointer != GENESIS ->
                    return Verdict.Rewritten("The first line does not start the chain.")

                index > 0 && pointer != previousDigest ->
                    return Verdict.Rewritten("Line $number does not point at the line before it.")
            }
            previousDigest = digest
        }

        return if (previousDigest == head) {
            Verdict.Extends(WitnessedHead(entries, head))
        } else {
            Verdict.Rewritten("The last line does not hash to the head the server stated.")
        }
    }

    /** The text the server signs (protocol §5.7). */
    fun statement(serverPin: String, devicePin: String, entries: Long, head: String, at: Long): String =
        "filum-ledger-head/1\n$serverPin\n$devicePin\n$entries\n$head\n$at\n"

    /**
     * Whether [response] is signed by the key [serverPin] fingerprints. The key is held up to
     * the pin first: a key the server merely sent along would prove nothing.
     */
    fun signedByServer(response: LedgerResponse, serverPin: String, devicePin: String): Boolean {
        val decoder = Base64.getUrlDecoder()
        val spki = runCatching { decoder.decode(response.key) }.getOrNull() ?: return false
        val pin = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))
        if (pin != serverPin) return false

        return runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(statement(serverPin, devicePin, response.entries, response.head, response.at).toByteArray(Charsets.UTF_8))
                verify(decoder.decode(response.signature))
            }
        }.getOrDefault(false)
    }

    /** A line's digest over its exact text, as protocol §9.2 defines it. */
    fun digestOf(line: String): String =
        MessageDigest.getInstance("SHA-256").digest(line.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun parse(line: String): JsonObject? =
        runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
}
