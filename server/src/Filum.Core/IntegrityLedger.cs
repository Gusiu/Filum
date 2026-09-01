using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Filum.Core;

/// <summary>
/// One entry in the integrity ledger — one accepted file.
///
/// The field names are short, because the ledger grows by a line per file and on a large
/// library its own size starts to matter.
/// </summary>
public sealed record LedgerEntry(
    [property: JsonPropertyName("n")] long Sequence,
    [property: JsonPropertyName("at")] long AcceptedAt,
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("s")] long Size,
    [property: JsonPropertyName("m")] long Mtime,
    [property: JsonPropertyName("d")] string Digest,
    /// <summary>The previous line's digest. This is what chains the ledger (protocol §9.2).</summary>
    [property: JsonPropertyName("prev")] string Previous);

public sealed record LedgerVerification(
    bool Intact,
    long Entries,
    long? BrokenAtSequence,
    string? Problem);

/// <summary>
/// The integrity ledger: an append-only record of everything the server accepted (ADR-016).
///
/// It answers a question the file copy alone cannot be asked: **what arrived here and
/// when, and has anyone touched it since**. For evidentiary use that is the right
/// question — detecting that a file changed is not enough on its own if there is nothing
/// to show how it looked before.
///
/// Every line carries the digest of the line before it. Removing or altering any entry
/// therefore breaks everything that follows — and a record that cannot be quietly
/// corrected is a qualitatively different thing from a list of digests.
///
/// **What this does not give.** The chain detects retroactive edits, but does not protect
/// against someone with access to the disk and the files rebuilding the whole ledger from
/// scratch. Signing it with the server key would change nothing, because that key sits on
/// the same machine. The honest guarantee reads: detectability of partial tampering, not
/// impossibility of tampering.
/// </summary>
public sealed class IntegrityLedger(StoragePaths paths)
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    /// <summary>
    /// UTF-8 **without a byte-order mark**.
    ///
    /// `Encoding.UTF8` writes a BOM when creating a file, which stops the first line from
    /// being valid JSON for anything that does not expect that convention. This ledger's
    /// entire value rests on being checkable **without** this application, in three lines
    /// of any language. A mark that breaks that is a defect, not a storage detail.
    /// </summary>
    private static readonly Encoding Utf8 = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);

    /// <summary>The value of `prev` in the first line. The chain's anchor.</summary>
    public const string Genesis = "genesis";

    private readonly object _gate = new();

    /// <summary>
    /// Path to most recently accepted digest, built lazily on the first question asked.
    ///
    /// Without it every check would walk the ledger from the start, and a manifest batch
    /// holds up to two thousand entries — against a ledger of a hundred thousand entries
    /// that would mean hundreds of millions of line reads per request.
    /// </summary>
    private readonly Dictionary<string, Dictionary<string, string>> _index = new(StringComparer.Ordinal);

    /// <summary>
    /// Appends an entry for an accepted file.
    ///
    /// The write is under a lock, because the chain requires reading the last line before
    /// appending the next. With concurrent transfers and no lock, two entries would point
    /// at the same predecessor and the chain would break immediately.
    /// </summary>
    public LedgerEntry Append(string slug, string canonicalPath, long size, long mtime, string digest)
    {
        lock (_gate)
        {
            var file = LedgerFile(slug);
            Directory.CreateDirectory(LongPath.Extended(Path.GetDirectoryName(file)!));

            var (sequence, previous) = TailOf(file);

            var entry = new LedgerEntry(
                Sequence: sequence + 1,
                AcceptedAt: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                Path: canonicalPath,
                Size: size,
                Mtime: mtime,
                Digest: digest,
                Previous: previous);

            File.AppendAllText(LongPath.Extended(file), Serialize(entry) + "\n", Utf8);

            // Keep the index in step with what was just written. Only when it already
            // exists: building it here would force a read of the whole ledger at a
            // moment when nobody had asked for it.
            if (_index.TryGetValue(slug, out var index)) index[canonicalPath] = digest;

            return entry;
        }
    }

    /// <summary>The last known digest for this path; null if it was never accepted.</summary>
    public string? DigestOf(string slug, string canonicalPath)
    {
        lock (_gate)
        {
            return IndexFor(slug).GetValueOrDefault(canonicalPath);
        }
    }

    /// <summary>Drops the built index; the next question re-reads the ledger.</summary>
    public void Invalidate(string slug)
    {
        lock (_gate) _index.Remove(slug);
    }

    private Dictionary<string, string> IndexFor(string slug)
    {
        if (_index.TryGetValue(slug, out var existing)) return existing;

        var built = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var entry in Read(slug)) built[entry.Path] = entry.Digest;

        _index[slug] = built;
        return built;
    }

    /// <summary>
    /// Checks that the chain holds.
    ///
    /// Walks the ledger from the start, recomputing digests. It reports the **first** line
    /// from which something no longer adds up — everything after it would be inconsistent
    /// anyway, so listing all of them would add nothing.
    /// </summary>
    public LedgerVerification Verify(string slug)
    {
        var expectedPrevious = Genesis;
        long expectedSequence = 1;
        long count = 0;

        foreach (var line in ReadLines(slug))
        {
            LedgerEntry? entry;
            try
            {
                entry = JsonSerializer.Deserialize<LedgerEntry>(line, Json);
            }
            catch (JsonException)
            {
                return new LedgerVerification(false, count, expectedSequence, "A line cannot be read.");
            }

            if (entry is null)
                return new LedgerVerification(false, count, expectedSequence, "An empty ledger line.");

            if (entry.Sequence != expectedSequence)
                return new LedgerVerification(false, count, entry.Sequence,
                    $"Entry number {entry.Sequence} where {expectedSequence} was expected — a line is missing or was inserted.");

            if (entry.Previous != expectedPrevious)
                return new LedgerVerification(false, count, entry.Sequence,
                    "An entry does not point at the previous line — the ledger was edited.");

            expectedPrevious = HashOf(line);
            expectedSequence++;
            count++;
        }

        return new LedgerVerification(true, count, null, null);
    }

    public IEnumerable<LedgerEntry> Read(string slug)
    {
        foreach (var line in ReadLines(slug))
        {
            LedgerEntry? entry = null;
            try
            {
                entry = JsonSerializer.Deserialize<LedgerEntry>(line, Json);
            }
            catch (JsonException)
            {
                // A corrupt line is skipped on read; detecting that is Verify's job, not
                // something every individual read should be doing.
            }

            if (entry is not null) yield return entry;
        }
    }

    public long Count(string slug) => ReadLines(slug).LongCount();

    private IEnumerable<string> ReadLines(string slug)
    {
        var file = LongPath.Extended(LedgerFile(slug));
        if (!File.Exists(file)) yield break;

        foreach (var line in File.ReadLines(file, Utf8))
        {
            if (line.Length > 0) yield return line;
        }
    }

    /// <summary>The last line's number and digest — the anchor for the next one.</summary>
    private (long Sequence, string Hash) TailOf(string file)
    {
        var extended = LongPath.Extended(file);
        if (!File.Exists(extended)) return (0, Genesis);

        string? last = null;
        foreach (var line in File.ReadLines(extended, Utf8))
        {
            if (line.Length > 0) last = line;
        }

        if (last is null) return (0, Genesis);

        var sequence = JsonSerializer.Deserialize<LedgerEntry>(last, Json)?.Sequence ?? 0;
        return (sequence, HashOf(last));
    }

    /// <summary>
    /// A line's digest, computed over its **exact textual form**.
    ///
    /// Not over the object's fields: were serialisation ever to change field order or
    /// number formatting, the entire existing chain would become inconsistent with nobody
    /// having tampered with anything. The line is what was written, and the line is what
    /// gets hashed.
    /// </summary>
    private static string HashOf(string line) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(line)));

    private static string Serialize(LedgerEntry entry) => JsonSerializer.Serialize(entry, Json);

    private string LedgerFile(string slug) => Path.Combine(paths.MetaRoot(slug), "integrity.jsonl");
}
