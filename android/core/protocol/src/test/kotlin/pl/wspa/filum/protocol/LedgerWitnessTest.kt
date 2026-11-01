package pl.wspa.filum.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Witnessing the server's ledger (protocol §9.5).
 *
 * The lines are written here the way the server writes them - field order, escapes and all -
 * because the digest is taken over the exact text, never over a re-serialised object.
 */
class LedgerWitnessTest {

    /** A ledger as the server stores it: each line points at the digest of the one before. */
    private fun ledger(vararg contents: String): List<String> {
        val lines = mutableListOf<String>()
        for ((index, content) in contents.withIndex()) {
            val previous = lines.lastOrNull()?.let(LedgerWitness::digestOf) ?: LedgerWitness.GENESIS
            val digest = LedgerWitness.digestOf(content)
            lines += """{"n":${index + 1},"at":1756400000,"p":"$content","s":${content.length},"m":1756400000,"d":"$digest","prev":"$previous"}"""
        }
        return lines
    }

    private fun headOf(lines: List<String>) = WitnessedHead(lines.size.toLong(), LedgerWitness.digestOf(lines.last()))

    private fun check(previous: WitnessedHead?, all: List<String>): LedgerWitness.Verdict {
        val from = LedgerWitness.startingLine(previous).toInt()
        return LedgerWitness.check(previous, all.drop(from - 1), all.size.toLong(), LedgerWitness.digestOf(all.last()))
    }

    @Test
    fun `the first witness takes a chain that starts at the anchor`() {
        val lines = ledger("a.txt", "b.txt", "c.txt")

        val verdict = check(null, lines)

        assertEquals(LedgerWitness.Verdict.Extends(headOf(lines)), verdict)
    }

    @Test
    fun `a later witness takes the lines that extend the head it holds`() {
        val lines = ledger("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")

        val verdict = check(headOf(lines.take(3)), lines)

        assertEquals(LedgerWitness.Verdict.Extends(headOf(lines)), verdict)
    }

    @Test
    fun `with nothing new the same head is witnessed again`() {
        val lines = ledger("a.txt", "b.txt", "c.txt")

        assertEquals(LedgerWitness.Verdict.Extends(headOf(lines)), check(headOf(lines), lines))
    }

    @Test
    fun `a ledger rebuilt from scratch is caught although it is consistent`() {
        // Every line points at the one before it - the chain alone finds nothing wrong. Only
        // the head the phone kept shows that this is not the history it saw.
        val original = ledger("a.txt", "incriminating.txt", "c.txt")
        val rebuilt = ledger("a.txt", "harmless.txt", "c.txt", "d.txt")

        val verdict = check(headOf(original), rebuilt)

        assertEquals("Line 3 is not the line witnessed.", (verdict as LedgerWitness.Verdict.Rewritten).reason)
    }

    @Test
    fun `a ledger cut below the witnessed head is caught`() {
        val lines = ledger("a.txt", "b.txt", "c.txt")

        val verdict = LedgerWitness.check(headOf(lines), emptyList(), 2, LedgerWitness.digestOf(lines[1]))

        assertInstanceOf(LedgerWitness.Verdict.Rewritten::class.java, verdict)
    }

    @Test
    fun `a new line that does not point at the one before it is caught`() {
        val lines = ledger("a.txt", "b.txt", "c.txt", "d.txt").toMutableList()
        lines[3] = lines[3].replace(Regex("\"prev\":\"[0-9a-f]+\""), "\"prev\":\"${"0".repeat(64)}\"")

        val verdict = check(headOf(lines.take(3)), lines)

        assertEquals("Line 4 does not point at the line before it.", (verdict as LedgerWitness.Verdict.Rewritten).reason)
    }

    @Test
    fun `a head the lines do not end in is caught`() {
        val lines = ledger("a.txt", "b.txt")

        val verdict = LedgerWitness.check(null, lines, 2, LedgerWitness.digestOf(lines[0]))

        assertInstanceOf(LedgerWitness.Verdict.Rewritten::class.java, verdict)
    }

    @Test
    fun `missing lines are caught`() {
        val lines = ledger("a.txt", "b.txt", "c.txt")

        val verdict = LedgerWitness.check(null, lines.take(2), 3, LedgerWitness.digestOf(lines[2]))

        assertInstanceOf(LedgerWitness.Verdict.Rewritten::class.java, verdict)
    }

    @Test
    fun `the digest is taken over the stored text, escapes included`() {
        // System.Text.Json writes ę as ę. A witness that parsed the line and serialised
        // it again would hash a different text and accuse an untouched ledger.
        val line = """{"n":1,"at":1756400000,"p":"Pictures/zdjęcie.jpg","s":5,"m":1756400000,"d":"${"a".repeat(64)}","prev":"genesis"}"""

        val verdict = LedgerWitness.check(null, listOf(line), 1, LedgerWitness.digestOf(line))

        assertEquals(LedgerWitness.Verdict.Extends(WitnessedHead(1, LedgerWitness.digestOf(line))), verdict)
    }

    @Test
    fun `a line of the wrong shape is a verdict, not an exception`() {
        // What the server sends is checked, not trusted - a malformed line must not take the
        // cycle down with it.
        val line = """{"n":{},"prev":"genesis"}"""

        val verdict = LedgerWitness.check(null, listOf(line), 1, LedgerWitness.digestOf(line))

        assertInstanceOf(LedgerWitness.Verdict.Rewritten::class.java, verdict)
    }

    @Test
    fun `an empty ledger has the anchor for a head`() {
        assertEquals(
            LedgerWitness.Verdict.Extends(WitnessedHead(0, LedgerWitness.GENESIS)),
            LedgerWitness.check(null, emptyList(), 0, LedgerWitness.GENESIS),
        )
    }

    // --- the server's signature (protocol §5.7) ---

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val spki = keys.public.encoded
    private val serverPin = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))
    private val devicePin = "device-pin"

    private fun signed(entries: Long, head: String, at: Long): LedgerResponse {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(LedgerWitness.statement(serverPin, devicePin, entries, head, at).toByteArray(Charsets.UTF_8))
            sign()
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return LedgerResponse(entries, head, 1, emptyList(), at, encoder.encodeToString(spki), encoder.encodeToString(signature))
    }

    @Test
    fun `a statement signed by the pinned key is accepted`() {
        assertTrue(LedgerWitness.signedByServer(signed(3, "a".repeat(64), 1793500000), serverPin, devicePin))
    }

    @Test
    fun `a key the pin does not name is refused, whatever it signed`() {
        assertFalse(LedgerWitness.signedByServer(signed(3, "a".repeat(64), 1793500000), "another-server-pin", devicePin))
    }

    @Test
    fun `a statement changed after signing is refused`() {
        val genuine = signed(3, "a".repeat(64), 1793500000)

        assertFalse(LedgerWitness.signedByServer(genuine.copy(entries = 2), serverPin, devicePin))
        assertFalse(LedgerWitness.signedByServer(genuine, serverPin, "another-device"))
    }
}
