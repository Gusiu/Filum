using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// Resolving a manifest (§5.2). The whole answer is produced by comparing against the
/// filesystem — the server has no content index (ADR-003), so these tests also check,
/// incidentally, whether the disk really does stand in for a database.
/// </summary>
public class ManifestResolverTests
{
    private const long Mtime = 1_756_400_000;

    private static ManifestEntry Entry(string path, long size, long mtime) => new(path, size, mtime);

    [Fact]
    public void AFileMatchingOnSizeAndMtime_CountsAsHeld()
    {
        using var h = new Harness();
        var content = Harness.Bytes("content");
        h.Seed("DCIM/Camera/IMG_0001.JPG", content, Mtime);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("DCIM/Camera/IMG_0001.JPG", content.Length, Mtime)]);

        Assert.Equal(1, result.Have);
        Assert.Empty(result.Needed);
    }

    [Fact]
    public void AnAbsentFile_LandsInNeededFromZero()
    {
        using var h = new Harness();

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("DCIM/Camera/IMG_0002.JPG", 1000, Mtime)]);

        Assert.Equal(0, result.Have);
        var needed = Assert.Single(result.Needed);
        Assert.Equal("DCIM/Camera/IMG_0002.JPG", needed.Path);
        Assert.Equal(0, needed.Offset);
        Assert.Null(needed.Versioned);
    }

    [Fact]
    public void AFileWithADifferentMtime_LandsInNeededFlaggedForVersioning()
    {
        using var h = new Harness();
        var content = Harness.Bytes("content");
        h.Seed("Documents/note.txt", content, Mtime);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Documents/note.txt", content.Length, Mtime + 60)]);

        Assert.Equal(0, result.Have);
        var needed = Assert.Single(result.Needed);
        Assert.Equal(0, needed.Offset);
        Assert.True(needed.Versioned);
    }

    [Fact]
    public void AFileWithADifferentSize_LandsInNeededFlaggedForVersioning()
    {
        using var h = new Harness();
        h.Seed("Documents/note.txt", Harness.Bytes("short"), Mtime);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Documents/note.txt", 9999, Mtime)]);

        Assert.True(Assert.Single(result.Needed).Versioned);
    }

    [Fact]
    public async Task AnUnfinishedTransfer_YieldsAResumeOffset()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789ABCDEF");
        await h.WriteAsync("Movies/clip.mp4", content[..6], content.Length, Mtime, 0);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Movies/clip.mp4", content.Length, Mtime)]);

        Assert.Equal(6, Assert.Single(result.Needed).Offset);
    }

    [Fact]
    public async Task PartFromADifferentVersion_IsNotUsedToResume()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789ABCDEF");
        await h.WriteAsync("Movies/clip.mp4", content[..6], content.Length, Mtime, 0);

        // the same file, but the phone reports a different mtime — the part is useless
        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Movies/clip.mp4", content.Length, Mtime + 30)]);

        Assert.Equal(0, Assert.Single(result.Needed).Offset);
    }

    [Fact]
    public void AnUnacceptablePath_LandsInRejected_NotInNeeded()
    {
        // Without this the client would learn of the rejection only from a 422 on PUT,
        // i.e. after one wasted request per such file.
        using var h = new Harness();

        var result = h.Manifest.Resolve(Harness.Slug,
        [
            Entry("../ucieczka.txt", 10, Mtime),
            Entry("DCIM/dobry.jpg", 10, Mtime),
        ]);

        Assert.Single(result.Needed);
        var rejected = Assert.Single(result.Rejected);
        Assert.Equal("../ucieczka.txt", rejected.Path);
        Assert.Equal("traversal", rejected.Error);
    }

    [Fact]
    public void AManuallyDeletedFile_IsReportedAsNeededAgain()
    {
        // A consequence of ADR-003: since the disk is the source of truth, deleting a
        // file in Explorer has to be detected and the file re-sent.
        using var h = new Harness();
        var content = Harness.Bytes("content");
        h.Seed("DCIM/Camera/IMG_0001.JPG", content, Mtime);

        var entries = new[] { Entry("DCIM/Camera/IMG_0001.JPG", content.Length, Mtime) };
        Assert.Equal(1, h.Manifest.Resolve(Harness.Slug, entries).Have);

        File.Delete(LongPath.Extended(h.TargetOf("DCIM/Camera/IMG_0001.JPG")));

        var after = h.Manifest.Resolve(Harness.Slug, entries);
        Assert.Equal(0, after.Have);
        Assert.Single(after.Needed);
    }

    [Fact]
    public void AnEmptyBatch_ReturnsAnEmptyAnswer()
    {
        using var h = new Harness();

        var result = h.Manifest.Resolve(Harness.Slug, []);

        Assert.Equal(0, result.Have);
        Assert.Empty(result.Needed);
        Assert.Empty(result.Rejected);
    }
}
