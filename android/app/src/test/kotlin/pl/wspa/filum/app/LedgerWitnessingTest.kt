package pl.wspa.filum.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.wspa.filum.data.WitnessedHeadDao
import pl.wspa.filum.data.WitnessedHeadEntity
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.protocol.LedgerResponse
import pl.wspa.filum.protocol.LedgerWitness
import pl.wspa.filum.sync.Deadline
import pl.wspa.filum.sync.JournalLevel
import pl.wspa.filum.sync.SyncJournal
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The phone's side of witnessing (protocol §9.5): paging through GET /ledger, and what each
 * answer of the server leaves behind - a head in the database, a line in the journal.
 */
class LedgerWitnessingTest {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val devicePin = "device-pin"

    /** A server's signing key; [pin] is what the phone holds from pairing. */
    private class ServerKey {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val spki: ByteArray = keys.public.encoded
        val pin: String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))
    }

    private val server = ServerKey()

    private class Heads : WitnessedHeadDao {
        val added = mutableListOf<WitnessedHeadEntity>()
        private val newest = MutableStateFlow<WitnessedHeadEntity?>(null)

        override suspend fun add(head: WitnessedHeadEntity) {
            added += head.copy(id = added.size + 1L)
            newest.value = added.last()
        }

        override suspend fun latest(serverPin: String) = added.lastOrNull { it.serverPin == serverPin }

        override fun observeLatest(): Flow<WitnessedHeadEntity?> = newest
    }

    private class Journal : SyncJournal {
        val events = mutableListOf<Triple<JournalLevel, String, String>>()

        override suspend fun record(level: JournalLevel, code: String, message: String) {
            events += Triple(level, code, message)
        }
    }

    private val heads = Heads()
    private val journal = Journal()
    private val witnessing = LedgerWitnessing(heads, journal, clock = { 42L })

    /** Ledger lines as the server writes them, each pointing at the digest of the one before. */
    private fun ledger(count: Int, name: (Int) -> String = { "file$it.txt" }): List<String> {
        val lines = mutableListOf<String>()
        for (index in 0 until count) {
            val previous = lines.lastOrNull()?.let(LedgerWitness::digestOf) ?: LedgerWitness.GENESIS
            val path = name(index)
            lines += """{"n":${index + 1},"at":1756400000,"p":"$path","s":1,"m":1756400000,"d":"${LedgerWitness.digestOf(path)}","prev":"$previous"}"""
        }
        return lines
    }

    /** The head this phone witnessed when the ledger held [lines]. */
    private suspend fun witnessed(lines: List<String>) = heads.add(
        WitnessedHeadEntity(
            serverPin = server.pin, entries = lines.size.toLong(), head = LedgerWitness.digestOf(lines.last()),
            statedAt = 0, serverKey = "", signature = "", witnessedAt = 0,
        )
    )

    /** GET /ledger over [lines], at most [page] lines an answer, the statement signed with [key]. */
    private inner class FakeLedger(var lines: List<String>, private val page: Int = 5, private val key: ServerKey = server) {
        val asked = mutableListOf<Long>()

        /** Runs before each answer, so a test can change the ledger between pages. */
        var beforeAnswer: (from: Long) -> Unit = {}

        suspend fun read(from: Long): ApiResult<LedgerResponse> {
            asked += from
            beforeAnswer(from)

            val entries = lines.size.toLong()
            val head = lines.lastOrNull()?.let(LedgerWitness::digestOf) ?: LedgerWitness.GENESIS
            val at = 1_793_500_000L
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(key.keys.private)
                update(LedgerWitness.statement(server.pin, devicePin, entries, head, at).toByteArray(Charsets.UTF_8))
                sign()
            }
            val slice = lines.drop((from - 1).toInt()).take(page)
            return ApiResult.Ok(
                LedgerResponse(entries, head, from, slice, at, encoder.encodeToString(key.spki), encoder.encodeToString(signature))
            )
        }
    }

    private fun codes() = journal.events.map { it.second }

    @Test
    fun `the first witness pages through the whole ledger and records its head`() = runBlocking {
        val lines = ledger(12)
        val fake = FakeLedger(lines, page = 5)

        witnessing.witness(fake::read, server.pin, devicePin)

        assertEquals(listOf(1L, 6L, 11L), fake.asked)
        val head = heads.added.single()
        assertEquals(12L, head.entries)
        assertEquals(LedgerWitness.digestOf(lines.last()), head.head)
        assertEquals(42L, head.witnessedAt)
        assertEquals(listOf("ledger_witnessed"), codes())
    }

    @Test
    fun `a later witness asks from the witnessed line on`() = runBlocking {
        val lines = ledger(5)
        witnessed(lines.take(3))
        val fake = FakeLedger(lines)

        witnessing.witness(fake::read, server.pin, devicePin)

        // Line 3 comes again, so that the line the phone saw is checked to be still the same.
        assertEquals(listOf(3L), fake.asked)
        assertEquals(5L, heads.added.last().entries)
    }

    @Test
    fun `a rewritten ledger goes into the journal and the last good head stays`() = runBlocking {
        val original = ledger(3) { listOf("a.txt", "incriminating.txt", "c.txt")[it] }
        witnessed(original)
        val fake = FakeLedger(ledger(4) { listOf("a.txt", "harmless.txt", "c.txt", "d.txt")[it] })

        witnessing.witness(fake::read, server.pin, devicePin)

        assertEquals(1, heads.added.size)
        val (level, code, message) = journal.events.single()
        assertEquals(JournalLevel.ERROR, level)
        assertEquals("ledger_rewritten", code)
        assertTrue("Line 3 is not the line witnessed." in message, message)
    }

    @Test
    fun `a statement signed by another key is not taken`() = runBlocking {
        val fake = FakeLedger(ledger(1), key = ServerKey())

        witnessing.witness(fake::read, server.pin, devicePin)

        assertTrue(heads.added.isEmpty())
        assertEquals(JournalLevel.WARN, journal.events.single().first)
        assertEquals(listOf("ledger_unverified"), codes())
    }

    @Test
    fun `a ledger that grows between pages is not taken`() = runBlocking {
        val fake = FakeLedger(ledger(8), page = 5)
        fake.beforeAnswer = { from -> if (from > 1) fake.lines = ledger(9) }

        witnessing.witness(fake::read, server.pin, devicePin)

        assertTrue(heads.added.isEmpty())
        assertEquals(listOf("ledger_unverified"), codes())
    }

    @Test
    fun `an unreachable server leaves the head as it was`() = runBlocking {
        witnessing.witness({ ApiResult.Unreachable(IOException("connection reset")) }, server.pin, devicePin)

        assertTrue(heads.added.isEmpty())
        assertEquals(listOf("ledger_unverified"), codes())
    }

    @Test
    fun `a check the budget cuts short records no head`() = runBlocking {
        val fake = FakeLedger(ledger(12), page = 5)

        witnessing.witness(fake::read, server.pin, devicePin, deadline = Deadline { fake.asked.isNotEmpty() })

        assertEquals(listOf(1L), fake.asked)
        assertTrue(heads.added.isEmpty())
        assertEquals(JournalLevel.INFO, journal.events.single().first)
        assertEquals(listOf("ledger_deferred"), codes())
    }
}
