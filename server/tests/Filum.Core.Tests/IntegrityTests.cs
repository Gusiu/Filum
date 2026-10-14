using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// The integrity ledger (ADR-016).
///
/// The ledger answers a question the file copy alone cannot be asked: what did the server
/// accept and when, and has anyone touched it since. The digest chain turns a list of
/// entries into a record that cannot be quietly corrected - and that is the only property
/// this ledger exists for, so that is what these tests aim at.
/// </summary>
public class IntegrityLedgerTests
{
    private const long Mtime = 1_756_400_000;

    private static string Digest(string content) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(content)));

    private static string LedgerPath(Harness h) =>
        Path.Combine(h.Paths.MetaRoot(Harness.Slug), "integrity.jsonl");

    private static LedgerEntry Append(Harness h, string path, string content) =>
        h.Ledger.Append(Harness.Slug, path, content.Length, Mtime, Digest(content));

    [Fact]
    public void TheFirstEntry_StartsTheChainAtTheAnchor()
    {
        using var h = new Harness();

        var entry = h.Ledger.Append(Harness.Slug, "a.txt", 10, Mtime, Digest("aaa"));

        Assert.Equal(1, entry.Sequence);
        Assert.Equal(IntegrityLedger.Genesis, entry.Previous);
    }

    [Fact]
    public void EveryEntry_PointsAtThePreviousOne()
    {
        using var h = new Harness();

        Append(h, "a.txt", "aaa");
        var second = Append(h, "b.txt", "bbb");

        Assert.Equal(2, second.Sequence);
        Assert.NotEqual(IntegrityLedger.Genesis, second.Previous);
    }

    [Fact]
    public void AnUntouchedLedger_IsIntact()
    {
        using var h = new Harness();
        for (var i = 1; i <= 20; i++) Append(h, $"file{i}.txt", $"content {i}");

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.True(result.Intact, result.Problem);
        Assert.Equal(20, result.Entries);
        Assert.Null(result.BrokenAtSequence);
    }

    [Fact]
    public void AnEmptyLedger_IsIntact()
    {
        using var h = new Harness();

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.True(result.Intact);
        Assert.Equal(0, result.Entries);
    }

    // --- the property the ledger exists for in the first place ---

    [Fact]
    public void AlteringAnEntry_IsDetected()
    {
        // Someone with disk access changes a digest in the ledger so that a substituted
        // file looks original. The entry itself looks perfectly fine - what exposes the
        // mismatch is the chain, because the next line points at the old form.
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Append(h, "b.txt", "bbb");
        Append(h, "c.txt", "ccc");

        var lines = File.ReadAllLines(LedgerPath(h));
        lines[1] = lines[1].Replace(Digest("bbb"), Digest("substituted"));
        File.WriteAllLines(LedgerPath(h), lines);

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.False(result.Intact);
        Assert.Equal(3, result.BrokenAtSequence);
    }

    [Fact]
    public void RemovingAnEntry_IsDetected()
    {
        // Deleting a line is the simplest way to make a file "never have existed".
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Append(h, "incriminating.txt", "bbb");
        Append(h, "c.txt", "ccc");

        var lines = File.ReadAllLines(LedgerPath(h)).ToList();
        lines.RemoveAt(1);
        File.WriteAllLines(LedgerPath(h), lines);

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.False(result.Intact);
        Assert.Contains("missing", result.Problem, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void InsertingAnEntryInTheMiddle_IsDetected()
    {
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Append(h, "b.txt", "bbb");

        var lines = File.ReadAllLines(LedgerPath(h)).ToList();
        lines.Insert(1, lines[0]);
        File.WriteAllLines(LedgerPath(h), lines);

        Assert.False(h.Ledger.Verify(Harness.Slug).Intact);
    }

    [Fact]
    public void AppendingAtTheEnd_IsValid()
    {
        // The ledger is append-only by definition - new entries must not invalidate it.
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);

        Append(h, "b.txt", "bbb");
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);
    }

    [Fact]
    public void ACorruptLine_IsDetected()
    {
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        File.AppendAllText(LedgerPath(h), "this is not json\n");

        Assert.False(h.Ledger.Verify(Harness.Slug).Intact);
    }

    [Fact]
    public void AFilesLatestDigest_ComesFromItsNewestEntry()
    {
        // A file may enter the ledger many times - what counts is the current state.
        using var h = new Harness();
        Append(h, "note.txt", "first version");
        Append(h, "note.txt", "second version");

        Assert.Equal(Digest("second version"), h.Ledger.DigestOf(Harness.Slug, "note.txt"));
    }

    [Fact]
    public void TheLedgerIsReadableByAForeignTool()
    {
        // The ledger's entire value rests on being checkable **without** this application.
        // A byte-order mark made the first line invalid JSON for standard parsers - and an
        // evidentiary record that needs knowledge of one implementation's storage
        // conventions to read is worth as much as trust in that implementation. Nothing.
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Append(h, "b.txt", "bbb");

        var raw = File.ReadAllBytes(LedgerPath(h));

        Assert.False(raw.Length >= 3 && raw[0] == 0xEF && raw[1] == 0xBB && raw[2] == 0xBF,
                     "the ledger starts with a byte-order mark");

        // Read without any involvement of our code - exactly as a third party would.
        foreach (var line in File.ReadAllLines(LedgerPath(h)).Where(l => l.Length > 0))
        {
            using var parsed = JsonDocument.Parse(line);
            Assert.True(parsed.RootElement.TryGetProperty("d", out _));
        }
    }

    [Fact]
    public void ADigestAfterAcceptance_IsVisibleDespiteAnEarlierQuery()
    {
        // The ledger keeps a path-to-digest index, built lazily on the first question asked.
        // Asking about a file that does not exist yet builds that index empty - and if the
        // write path does not refresh it, the next question answers out of date. In
        // thorough mode that means reporting a divergence for a file the server had just
        // correctly accepted.
        using var h = new Harness();

        Assert.Null(h.Ledger.DigestOf(Harness.Slug, "note.txt"));

        Append(h, "note.txt", "content");

        Assert.Equal(Digest("content"), h.Ledger.DigestOf(Harness.Slug, "note.txt"));
    }

    [Fact]
    public void AnUnknownFile_HasNoDigest()
    {
        using var h = new Harness();

        Assert.Null(h.Ledger.DigestOf(Harness.Slug, "never-seen.txt"));
    }
}

/// <summary>The digest requirement on accepting a file (ADR-016).</summary>
public class DigestRequirementTests
{
    private const long Mtime = 1_756_400_000;

    [Fact]
    public async Task WithTheRequirementOn_AFileWithoutADigestIsRefused()
    {
        using var h = new Harness(o => o.RequireDigest = true);

        var outcome = await h.WriteWholeAsync("a.txt", Harness.Bytes("content"), Mtime, withDigest: false);

        Assert.Equal(ChunkStatus.DigestRequired, outcome.Status);
        Assert.False(h.StoredExists("a.txt"));
    }

    [Fact]
    public async Task WithTheRequirementOn_AFileWithADigestPasses()
    {
        using var h = new Harness(o => o.RequireDigest = true);

        var outcome = await h.WriteWholeAsync("a.txt", Harness.Bytes("content"), Mtime, withDigest: true);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(h.StoredExists("a.txt"));
    }

    [Fact]
    public async Task RejectionBeforeWriting_LeavesNoPartFile()
    {
        // It matters that the refusal happens before anything reaches the disk: otherwise
        // every request without a digest would leave litter in the incoming directory.
        using var h = new Harness(o => o.RequireDigest = true);

        await h.WriteWholeAsync("a.txt", Harness.Bytes("content"), Mtime, withDigest: false);

        Assert.Equal(-1, h.PartLength("a.txt"));
    }

    [Fact]
    public async Task AnAcceptedFile_LandsInTheLedger()
    {
        using var h = new Harness(o => o.RequireDigest = true);
        var content = Harness.Bytes("content for the ledger");

        await h.WriteWholeAsync("DCIM/a.jpg", content, Mtime, withDigest: true);

        var entry = h.Ledger.Read(Harness.Slug).Single();
        Assert.Equal("DCIM/a.jpg", entry.Path);
        Assert.Equal(content.Length, entry.Size);
        Assert.Equal(Mtime, entry.Mtime);
        Assert.Equal(Harness.Digest(content), entry.Digest);
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);
    }

    [Fact]
    public async Task AFailedTransfer_LeavesNoEntry()
    {
        // An entry for a file that ultimately never landed on disk would be worse than no
        // entry - it would claim possession of something that is not there.
        using var h = new Harness(o => o.RequireDigest = true);
        var wrongDigest = Harness.Digest(Harness.Bytes("something else entirely"));

        await h.WriteAsync("a.txt", Harness.Bytes("content"), 7, Mtime, 0, wrongDigest);

        Assert.Empty(h.Ledger.Read(Harness.Slug));
    }

    [Fact]
    public async Task SuccessiveVersionsOfAFile_LeaveATraceOfBoth()
    {
        // The ledger records acceptances, not current state. The previous version of the
        // file sits in .filum/versions and its digest stays in the ledger - together they
        // give a reconstructible history rather than a snapshot.
        using var h = new Harness(o => o.RequireDigest = true);

        await h.WriteWholeAsync("note.txt", Harness.Bytes("first"), Mtime, withDigest: true);
        await h.WriteWholeAsync("note.txt", Harness.Bytes("second"), Mtime + 100, withDigest: true);

        var entries = h.Ledger.Read(Harness.Slug).ToList();
        Assert.Equal(2, entries.Count);
        Assert.Equal(Harness.Digest(Harness.Bytes("first")), entries[0].Digest);
        Assert.Equal(Harness.Digest(Harness.Bytes("second")), entries[1].Digest);
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);
    }
}

/// <summary>
/// Thorough mode: the manifest carries a digest and the server compares it against the
/// ledger (ADR-016).
///
/// The point is the difference between the two modes. Fast mode compares metadata and has
/// no way of noticing a substitution that preserved it - that is the known price of
/// ADR-002, not a defect. Thorough mode notices, because it asks a different question.
/// </summary>
public class ThoroughModeTests
{
    private const long Mtime = 1_756_400_000;

    /// <summary>Places a file and records it in the ledger - the state after a successful sync.</summary>
    private static void Accept(Harness h, string path, string content)
    {
        var bytes = Harness.Bytes(content);
        h.Seed(path, bytes, Mtime);
        h.Ledger.Append(Harness.Slug, path, bytes.Length, Mtime, Harness.Digest(bytes));
    }

    /// <summary>Substitutes the content while preserving size and modification time.</summary>
    private static void SubstituteInPlace(Harness h, string path, string content)
    {
        h.Seed(path, Harness.Bytes(content), Mtime);
    }

    private static ManifestEntry Entry(string path, string content, bool withDigest) =>
        new(path, Harness.Bytes(content).Length, Mtime,
            withDigest ? Harness.Digest(Harness.Bytes(content)) : null);

    [Fact]
    public void AMatchingDigest_CountsTheFileAsHeld()
    {
        using var h = new Harness();
        Accept(h, "note.txt", "content");

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("note.txt", "content", withDigest: true)]);

        Assert.Equal(1, result.Have);
        Assert.Empty(result.Needed);
        Assert.Empty(result.Divergent);
    }

    [Fact]
    public void AMetadataPreservingSubstitution_IsDetected()
    {
        // Someone changed the file on the phone so that size and mtime stayed untouched.
        // To fast mode this is the same file. The digest says otherwise.
        using var h = new Harness();
        Accept(h, "contract.pdf", "original text");
        SubstituteInPlace(h, "contract.pdf", "swapped text.");

        var result = h.Manifest.Resolve(
            Harness.Slug, [Entry("contract.pdf", "swapped text.", withDigest: true)]);

        Assert.Equal("contract.pdf", Assert.Single(result.Divergent));
        Assert.Equal(0, result.Have);
    }

    [Fact]
    public void ADetectedSubstitution_IsQueuedWithVersioning()
    {
        // A divergence must not end at the report: the copy is to be brought up to date and
        // the previous version filed away, so that the two can be compared.
        using var h = new Harness();
        Accept(h, "contract.pdf", "original text");
        SubstituteInPlace(h, "contract.pdf", "swapped text.");

        var result = h.Manifest.Resolve(
            Harness.Slug, [Entry("contract.pdf", "swapped text.", withDigest: true)]);

        var needed = Assert.Single(result.Needed);
        Assert.Equal("contract.pdf", needed.Path);
        Assert.Equal(0, needed.Offset);
        Assert.True(needed.Versioned);
    }

    [Fact]
    public void FastMode_DoesNotDetectAMetadataPreservingSubstitution()
    {
        // This test documents a limitation, not a wish. Without a digest the server has
        // only size and time to go on - and those agree. That is precisely why thorough
        // mode exists.
        using var h = new Harness();
        Accept(h, "contract.pdf", "original text");
        SubstituteInPlace(h, "contract.pdf", "swapped text.");

        var result = h.Manifest.Resolve(
            Harness.Slug, [Entry("contract.pdf", "swapped text.", withDigest: false)]);

        Assert.Equal(1, result.Have);
        Assert.Empty(result.Divergent);
    }

    [Fact]
    public void AFileOutsideTheLedger_IsNotReportedAsDivergent()
    {
        // A file placed into the copy by hand, or accepted before the ledger was running.
        // There is nothing to compare against, and a guess would be a false alarm on the
        // first thorough scan - which is exactly when an alarm has to mean something.
        using var h = new Harness();
        h.Seed("old.txt", Harness.Bytes("content"), Mtime);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("old.txt", "content", withDigest: true)]);

        Assert.Equal(1, result.Have);
        Assert.Empty(result.Divergent);
    }

    [Fact]
    public void ASizeChange_IsReportedAsAnOrdinaryUpdate()
    {
        // A divergence is meant to say "the metadata is lying". An ordinary edit changes
        // mtime or size and is nothing suspicious - conflating the two would strip the
        // report of its value.
        using var h = new Harness();
        Accept(h, "note.txt", "short");

        var result = h.Manifest.Resolve(
            Harness.Slug, [Entry("note.txt", "decidedly longer content", withDigest: true)]);

        Assert.Single(result.Needed);
        Assert.Empty(result.Divergent);
    }
}
