package pl.wspa.filum.app

import pl.wspa.filum.data.WitnessedHeadDao
import pl.wspa.filum.data.WitnessedHeadEntity
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.protocol.LedgerResponse
import pl.wspa.filum.protocol.LedgerWitness
import pl.wspa.filum.protocol.WitnessedHead
import pl.wspa.filum.sync.Deadline
import pl.wspa.filum.sync.JournalLevel
import pl.wspa.filum.sync.SyncJournal

/**
 * Witnessing the server's ledger after a cycle (protocol §9.5).
 *
 * A head is recorded only when it extends the last one witnessed. Anything else goes into the
 * journal and leaves the last good head in place, so every later cycle checks against it again
 * rather than against whatever the server now claims.
 */
class LedgerWitnessing(
    private val heads: WitnessedHeadDao,
    private val journal: SyncJournal,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * [read] fetches one page of the ledger from the given line on - GET /ledger over the
     * route the cycle used. Pages are asked for while [deadline] allows; a check cut short
     * records no head, and the next one starts again from the same place.
     */
    suspend fun witness(
        read: suspend (from: Long) -> ApiResult<LedgerResponse>,
        serverPin: String,
        devicePin: String,
        deadline: Deadline = Deadline { false },
    ) {
        val last = heads.latest(serverPin)
        val previous = last?.let { WitnessedHead(it.entries, it.head) }

        val lines = mutableListOf<String>()
        var from = LedgerWitness.startingLine(previous)
        var statement: LedgerResponse? = null

        while (true) {
            if (deadline.reached()) return deferred()

            val page = when (val result = read(from)) {
                is ApiResult.Ok -> result.value
                is ApiResult.Failure -> return unverified("the server refused: ${result.message}")
                is ApiResult.Unreachable -> return unverified(result.message)
            }
            // Only this device adds to its ledger, and its cycle is over - a length that moves
            // between pages means the pages do not describe one ledger.
            if (statement != null && page.entries != statement.entries) {
                return unverified("the ledger changed while it was being read")
            }
            statement = page
            lines += page.lines
            from += page.lines.size
            if (page.lines.isEmpty() || from > page.entries) break
        }

        val stated = statement ?: return
        if (!LedgerWitness.signedByServer(stated, serverPin, devicePin)) {
            return unverified("the statement is not signed by the server's key")
        }

        when (val verdict = LedgerWitness.check(previous, lines, stated.entries, stated.head)) {
            is LedgerWitness.Verdict.Extends -> {
                heads.add(
                    WitnessedHeadEntity(
                        serverPin = serverPin,
                        entries = verdict.head.entries,
                        head = verdict.head.digest,
                        statedAt = stated.at,
                        serverKey = stated.key,
                        signature = stated.signature,
                        witnessedAt = clock(),
                    )
                )
                // The whole digest, so the head can be checked elsewhere with
                // tools/verify_ledger.py --head entries:digest.
                journal.record(
                    JournalLevel.INFO, "ledger_witnessed",
                    "Ledger witnessed: ${verdict.head.entries} entries, head ${verdict.head.digest}.",
                )
            }

            is LedgerWitness.Verdict.Rewritten -> journal.record(
                JournalLevel.ERROR, "ledger_rewritten",
                "The server's ledger no longer matches what this phone witnessed" +
                    (last?.let { " (${it.entries} entries, head ${it.head})" } ?: "") + ": ${verdict.reason}",
            )
        }
    }

    private suspend fun unverified(reason: String) =
        journal.record(JournalLevel.WARN, "ledger_unverified", "The server's ledger could not be checked: $reason.")

    private suspend fun deferred() =
        journal.record(JournalLevel.INFO, "ledger_deferred", "The ledger check ran out of time; the next cycle starts it again.")
}
