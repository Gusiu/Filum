package pl.wspa.filum.app

import pl.wspa.filum.crypto.DeviceIdentity
import pl.wspa.filum.crypto.FilumTls
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.EndpointRacer
import pl.wspa.filum.net.FilumApiClient
import pl.wspa.filum.net.FilumHttpClients
import pl.wspa.filum.net.RaceOutcome
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.protocol.VerifyEntry
import pl.wspa.filum.scan.ExclusionRules
import pl.wspa.filum.scan.FilesystemScanner
import pl.wspa.filum.scan.ScannedFile
import pl.wspa.filum.sync.JournalLevel
import java.io.File
import java.security.MessageDigest

sealed interface VerificationResult {
    data class Done(val checked: Int, val corrupted: List<String>, val missing: List<String>) : VerificationResult
    data class Failed(val reason: String) : VerificationResult
}

/**
 * Integrity checking of the copy (protocol §5.5, ADR-002).
 *
 * This is not the same as the change detection in §9.3. That asks whether a file on the phone
 * is still the same file; this operation asks whether what lies on the computer's disk is
 * still what we sent there. Storage media can silently corrupt a byte, and a backup that
 * does not know it is a backup of an illusion.
 *
 * We check a **sample**, not everything: a full verification would mean reading the
 * phone's entire storage and the server's entire disk, while a random sample detects
 * systematic media failure at a cost measured in seconds.
 */
class VerificationRunner(private val container: AppContainer) {

    suspend fun verifySample(sampleSize: Int = DEFAULT_SAMPLE): VerificationResult {
        val server = container.database.pairedServers().get()
            ?: return VerificationResult.Failed("The application is not paired with a server.")

        val identity = DeviceIdentity.loadOrCreate(server.keyAlias)
        val clients = FilumHttpClients(
            tls = FilumTls.bundle(server.serverPin, identity),
            binder = container.networkBinder,
        )

        val endpoint = findServer(clients)
            ?: return VerificationResult.Failed("Nie znaleziono serwera.")

        val sample = sample(sampleSize)
        if (sample.isEmpty()) return VerificationResult.Failed("The scan found no files to check.")

        val entries = sample.mapNotNull { file ->
            digestOf(File(file.absolutePath))?.let { VerifyEntry(file.canonicalPath, it) }
        }

        val api = FilumApiClient(clients.forKind(endpoint.kind))

        return when (val result = api.verify(endpoint, entries)) {
            is ApiResult.Ok -> {
                val response = result.value
                journal(
                    if (response.mismatch.isEmpty()) JournalLevel.INFO else JournalLevel.ERROR,
                    "verified",
                    "Checked ${entries.size} files: ${response.ok.size} matching, " +
                        "${response.mismatch.size} corrupted, ${response.missing.size} missing.",
                )
                VerificationResult.Done(entries.size, response.mismatch, response.missing)
            }

            is ApiResult.Failure -> VerificationResult.Failed(result.message)
            is ApiResult.Unreachable -> VerificationResult.Failed(result.message)
        }
    }

    private suspend fun findServer(clients: FilumHttpClients): ServerEndpoint? {
        val candidates = container.database.endpoints().all().mapNotNull { entity ->
            val kind = runCatching { EndpointKind.valueOf(entity.kind) }.getOrDefault(EndpointKind.MANUAL)
            ServerEndpoint(entity.host, entity.port, kind)
        }

        val race = EndpointRacer(
            probe = { endpoint -> FilumApiClient(clients.forProbe(endpoint.kind)).ping(endpoint) }
        ).fastest(candidates)

        return (race as? RaceOutcome.Reached)?.endpoint
    }

    /**
     * A stratified random sample from the current scan.
     *
     * Random rather than "the first N": media failure does not begin at the start of the
     * alphabet, and a fixed sample would check the same files over and over.
     *
     * Stratified, because plain sampling with a size cap had a flaw the result does not show:
     * files above the threshold entered the sample **never**. On a typical phone library that
     * meant video recordings — the data most expensive to reproduce — were the only category
     * permanently outside any checking at all. A separate pool for large files costs a few
     * extra reads and removes that whole blind spot.
     *
     */
    private suspend fun sample(size: Int): List<ScannedFile> {
        val exclusions = ExclusionRules(
            userPrefixes = container.database.exclusions().active().map { it.pathPrefix }
        )
        val root = File(pl.wspa.filum.scan.Environment.externalStorageRootPath())

        val (large, small) = FilesystemScanner(root, exclusions).walk()
            .toList()
            .partition { it.size > LARGE_FILE_BYTES }

        val fromLarge = large.shuffled().take(size / LARGE_SHARE_DIVISOR)

        // The unused part of a pool goes to the other stratum rather than shrinking the
        // sample: a phone with no large files should check as many as any other.
        return (fromLarge + small.shuffled().take(size - fromLarge.size)).shuffled()
    }

    private fun digestOf(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private suspend fun journal(level: JournalLevel, code: String, message: String) =
        RoomJournal(container.database.log()).record(level, code, message)

    private companion object {
        /** The server's limit for one request is 256 entries (§5.5). */
        const val DEFAULT_SAMPLE = 100

        /**
         * From this size a file counts towards the "large" pool.
         *
         * The threshold does not exclude anything from the sample, it splits it into two strata.
         * Both the phone **and** the server hash, so with uniform sampling one run of video
         * recordings would turn the check into a multi-minute read on both sides.
         */
        const val LARGE_FILE_BYTES = 32L * 1024 * 1024

        /**
         * What share of the sample is reserved for large files — here, one fifth.
         *
         * They are rarer but occupy most of the space. A share proportional to file count would
         * mean that among thousands of photos a recording entered the sample once every few
         * dozen checks.
         */
        const val LARGE_SHARE_DIVISOR = 5
    }
}
