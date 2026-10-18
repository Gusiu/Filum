using System.Security.Cryptography;
using System.Text;
using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// Integrity checking (protocol §5.5).
///
/// The one place where the server hashes existing files. Change detection stays
/// digest-free by default (I5) - verification answers a different question: is what lies
/// on the disk still what arrived from the phone.
/// </summary>
public class VerifyServiceTests
{
    private const long Mtime = 1_756_400_000;

    private static string Digest(string content) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(content)));

    private static VerifyService Service(Harness h) => new(h.Paths, h.Options);

    [Fact]
    public async Task AnIntactFile_IsConfirmed()
    {
        using var h = new Harness();
        h.Seed("DCIM/a.jpg", Harness.Bytes("content"), Mtime);

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("DCIM/a.jpg", Digest("content"))],
            CancellationToken.None);

        Assert.Equal(["DCIM/a.jpg"], result.Ok);
        Assert.Empty(result.Mismatch);
        Assert.Empty(result.Missing);
    }

    [Fact]
    public async Task ACorruptedByte_IsDetected()
    {
        // This is the whole reason the mode exists: storage media can silently corrupt
        // content while size and mtime stay unchanged, so ordinary change detection
        // detection would not see it.
        using var h = new Harness();
        h.Seed("DCIM/a.jpg", Harness.Bytes("content after a fault"), Mtime);

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("DCIM/a.jpg", Digest("original content"))],
            CancellationToken.None);

        Assert.Equal(["DCIM/a.jpg"], result.Mismatch);
        Assert.Empty(result.Ok);
    }

    [Fact]
    public async Task MissingFile_IsReportedSeparately()
    {
        // Missing and corrupted mean different things: the first calls for re-sending the
        // file, the second points at the storage medium.
        using var h = new Harness();

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("not-here.jpg", Digest("anything"))],
            CancellationToken.None);

        Assert.Equal(["not-here.jpg"], result.Missing);
        Assert.Empty(result.Mismatch);
    }

    [Fact]
    public async Task AnUnacceptablePath_IsTreatedAsMissing()
    {
        using var h = new Harness();

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("../escape.txt", Digest("x"))],
            CancellationToken.None);

        Assert.Equal(["../escape.txt"], result.Missing);
    }

    [Fact]
    public async Task ManyPathsAtOnce_SplitIntoThreeLists()
    {
        using var h = new Harness();
        h.Seed("good.txt", Harness.Bytes("aaa"), Mtime);
        h.Seed("broken.txt", Harness.Bytes("bbb"), Mtime);

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [
                new VerifyEntry("good.txt", Digest("aaa")),
                new VerifyEntry("broken.txt", Digest("something else")),
                new VerifyEntry("absent.txt", Digest("ccc")),
            ],
            CancellationToken.None);

        Assert.Equal(["good.txt"], result.Ok);
        Assert.Equal(["broken.txt"], result.Mismatch);
        Assert.Equal(["absent.txt"], result.Missing);
    }
}

/// <summary>
/// The orphan report (protocol §5.6, ADR-015).
///
/// The server never deletes anything, so moving files around on the phone multiplies
/// copies. The report gives the user a basis for a decision - and that is where it stops.
/// </summary>
public class OrphanReporterTests
{
    private const long Mtime = 1_756_400_000;

    private static OrphanReporter Reporter(Harness h) => new(h.Paths);

    private static OrphanReport Complete(Harness h, string session, params string[] reported)
    {
        var reporter = Reporter(h);
        reporter.RecordBatch(Harness.Slug, session, reported);
        return reporter.Complete(Harness.Slug, session);
    }

    [Fact]
    public void AFileAbsentFromTheManifest_IsAnOrphan()
    {
        using var h = new Harness();
        h.Seed("DCIM/remained.jpg", Harness.Bytes("aaa"), Mtime);
        h.Seed("DCIM/deleted.jpg", Harness.Bytes("bbbbb"), Mtime);

        var report = Complete(h, "session-1", "DCIM/remained.jpg");

        Assert.Equal(1, report.Count);
        Assert.Equal(["DCIM/deleted.jpg"], report.Paths);
        Assert.Equal(5, report.Bytes);
    }

    [Fact]
    public void OrphansAreComputedFromTheMergedBatches()
    {
        // A session covering a hundred thousand files is fifty requests; the report has to
        // see the sum of all of them, not just the last batch.
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);
        h.Seed("b.txt", Harness.Bytes("b"), Mtime);
        h.Seed("c.txt", Harness.Bytes("c"), Mtime);

        var reporter = Reporter(h);
        reporter.RecordBatch(Harness.Slug, "session-2", ["a.txt"]);
        reporter.RecordBatch(Harness.Slug, "session-2", ["b.txt"]);
        var report = reporter.Complete(Harness.Slug, "session-2");

        Assert.Equal(["c.txt"], report.Paths);
    }

    [Fact]
    public void OrphansAreReportedInCanonicalForm()
    {
        // On disk the file sits under its sanitised name, but the phone knows only the
        // canonical form - a report in disk form would be useless to it.
        using var h = new Harness();
        h.Seed("Documents/report:v2.txt", Harness.Bytes("x"), Mtime);

        var report = Complete(h, "session-3");

        Assert.Equal(["Documents/report:v2.txt"], report.Paths);
    }

    [Fact]
    public void TheReportCanBeReadOnceTheSessionCloses()
    {
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);

        Complete(h, "session-4");
        var loaded = Reporter(h).Read(Harness.Slug, "session-4");

        Assert.NotNull(loaded);
        Assert.Equal(["a.txt"], loaded.Paths);
    }

    [Fact]
    public void AReportForAnUnknownSession_DoesNotExist()
    {
        using var h = new Harness();

        Assert.Null(Reporter(h).Read(Harness.Slug, "never-existed"));
    }

    [Fact]
    public void SessionScratchFiles_AreCleanedUpAfterwards()
    {
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);

        Complete(h, "session-5", "a.txt");

        var sessions = Path.Combine(h.Paths.MetaRoot(Harness.Slug), "sessions");
        Assert.True(!Directory.Exists(sessions) || Directory.GetFiles(sessions).Length == 0);
    }

    [Fact]
    public void TheReportDeletesNothing()
    {
        // Invariant I1 holds here too. The report informs; the decision belongs to the
        // user and is made outside the application.
        using var h = new Harness();
        h.Seed("orphan.txt", Harness.Bytes("content"), Mtime);

        Complete(h, "session-6");

        Assert.True(h.StoredExists("orphan.txt"));
    }

    [Fact]
    public void SessionIdentifier_CannotEscapeTheDirectory()
    {
        // The identifier comes from the client and ends up in a filename.
        using var h = new Harness();

        Complete(h, "../../../malicious");

        var meta = h.Paths.MetaRoot(Harness.Slug);
        Assert.True(Directory.GetFiles(meta, "orphans-*.json").Length == 1);
        Assert.False(File.Exists(Path.Combine(h.Root, "malicious.json")));
    }

    [Fact]
    public void TheLatestReport_IsAvailableToThePanel()
    {
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);

        Complete(h, "older");
        Complete(h, "newer", "a.txt");

        var latest = Reporter(h).Latest(Harness.Slug);

        Assert.NotNull(latest);
        Assert.Empty(latest.Paths);
    }
}
