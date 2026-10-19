using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// The transfer lifecycle (protocol §5.3 and §8.2). This is where a mistake means a
/// corrupted or lost file belonging to the user, so the tests aim mostly at the failure
/// paths rather than the happy one.
/// </summary>
public class TransferStoreTests
{
    private const long Mtime = 1_756_400_000;

    [Fact]
    public async Task AnUpload_InOnePiece_LandsInFiles()
    {
        using var h = new Harness();
        var content = Harness.Bytes("photo content");

        var outcome = await h.WriteWholeAsync("DCIM/Camera/IMG_0001.JPG", content, Mtime);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(outcome.Complete);
        Assert.Equal(content, h.ReadStored("DCIM/Camera/IMG_0001.JPG"));
        Assert.Equal(Mtime, h.StoredMtime("DCIM/Camera/IMG_0001.JPG"));
    }

    [Fact]
    public async Task DuringATransfer_TheFileNeverAppearsInFiles()
    {
        // Invariant I3: the user is to see a consistent tree at any moment, so partial
        // files must never be visible inside files/.
        using var h = new Harness();
        var content = Harness.Bytes("0123456789");

        var outcome = await h.WriteAsync("Movies/clip.mp4", content[..4], content.Length, Mtime, 0);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.False(outcome.Complete);
        Assert.False(h.StoredExists("Movies/clip.mp4"));
        Assert.Equal(4, h.PartLength("Movies/clip.mp4"));
    }

    [Fact]
    public async Task Resuming_ReassemblesTheFileCorrectly()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789ABCDEF");

        await h.WriteAsync("Movies/clip.mp4", content[..6], content.Length, Mtime, 0);
        var outcome = await h.WriteAsync("Movies/clip.mp4", content[6..], content.Length, Mtime, 6);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(outcome.Complete);
        Assert.Equal(content, h.ReadStored("Movies/clip.mp4"));
    }

    [Fact]
    public async Task Resuming_AtAWrongOffset_ReportsTheExpectedOne()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789");

        await h.WriteAsync("Movies/clip.mp4", content[..4], content.Length, Mtime, 0);
        var outcome = await h.WriteAsync("Movies/clip.mp4", content[7..], content.Length, Mtime, 7);

        Assert.Equal(ChunkStatus.OffsetMismatch, outcome.Status);
        Assert.Equal(4, outcome.ExpectedOffset);
    }

    [Fact]
    public async Task Resuming_WhenTheFileChanged_ForcesAStartFromZero()
    {
        // A real-life scenario: a video recording was still being written on the previous
        // attempt. Splicing the old part onto new bytes would produce a file that never
        // existed - worse than no file, because it looks correct.
        using var h = new Harness();
        var first = Harness.Bytes("0123456789");

        await h.WriteAsync("Movies/clip.mp4", first[..4], first.Length, Mtime, 0);
        var outcome = await h.WriteAsync("Movies/clip.mp4", first[4..], 20, Mtime + 5, 4);

        Assert.Equal(ChunkStatus.OffsetMismatch, outcome.Status);
        Assert.Equal(0, outcome.ExpectedOffset);
    }

    [Fact]
    public async Task Commit_WithACorrectDigest_Succeeds()
    {
        using var h = new Harness();
        var content = Harness.Bytes("data to check");

        var outcome = await h.WriteWholeAsync("Documents/file.txt", content, Mtime, withDigest: true);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(outcome.Complete);
    }

    [Fact]
    public async Task Commit_WithAWrongDigest_DropsThePartAndCreatesNoFile()
    {
        using var h = new Harness();
        var content = Harness.Bytes("data to check");
        var wrongDigest = Harness.Digest(Harness.Bytes("something else entirely"));

        var outcome = await h.WriteAsync("Documents/file.txt", content, content.Length, Mtime, 0, wrongDigest);

        Assert.Equal(ChunkStatus.DigestMismatch, outcome.Status);
        Assert.False(h.StoredExists("Documents/file.txt"));
        Assert.Equal(-1, h.PartLength("Documents/file.txt"));
    }

    [Fact]
    public async Task ExcessBytes_AreReported_NotSilentlyTruncated()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789");

        // declare 5 bytes, send 10
        var outcome = await h.WriteAsync("Documents/file.txt", content, 5, Mtime, 0);

        Assert.Equal(ChunkStatus.TooMuchData, outcome.Status);
        Assert.False(h.StoredExists("Documents/file.txt"));
    }

    // --- invariant I2: we never overwrite ---

    [Fact]
    public async Task ANewVersion_FilesTheOldCopyIntoVersions()
    {
        using var h = new Harness();
        var oldContent = Harness.Bytes("first version");
        var updated = Harness.Bytes("second version, longer");

        h.Seed("Documents/note.txt", oldContent, Mtime);
        var outcome = await h.WriteWholeAsync("Documents/note.txt", updated, Mtime + 3600);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.NotNull(outcome.VersionedPath);
        Assert.Equal(updated, h.ReadStored("Documents/note.txt"));

        var archived = h.VersionFiles();
        Assert.Single(archived);

        var versionAbsolute = Path.Combine(h.Paths.DeviceRoot(Harness.Slug),
                                           outcome.VersionedPath!.Replace('/', Path.DirectorySeparatorChar));
        Assert.Equal(oldContent, File.ReadAllBytes(LongPath.Extended(versionAbsolute)));
    }

    [Fact]
    public async Task VersionName_ComesFromTheOldCopysMtime_NotFromUploadTime()
    {
        using var h = new Harness();
        // 2025-01-15 10:30:00 UTC
        const long oldMtime = 1_736_937_000;

        h.Seed("Documents/note.txt", Harness.Bytes("oldContent"), oldMtime);
        await h.WriteWholeAsync("Documents/note.txt", Harness.Bytes("updated"), Mtime);

        var expected = DateTimeOffset.FromUnixTimeSeconds(oldMtime).UtcDateTime.ToString("yyyyMMdd'T'HHmmss'Z'");
        Assert.Equal($"note.{expected}.txt", h.VersionFiles().Single());
    }

    [Fact]
    public async Task TwoSuccessiveVersions_AreBothKept()
    {
        using var h = new Harness();

        h.Seed("Documents/note.txt", Harness.Bytes("first"), Mtime);
        await h.WriteWholeAsync("Documents/note.txt", Harness.Bytes("second"), Mtime + 100);
        await h.WriteWholeAsync("Documents/note.txt", Harness.Bytes("third"), Mtime + 200);

        Assert.Equal(2, h.VersionFiles().Count);
        Assert.Equal(Harness.Bytes("third"), h.ReadStored("Documents/note.txt"));
    }

    [Fact]
    public async Task ReUploadingTheSameVersion_DoesNotOverwriteTheArchive()
    {
        // The same (size, mtime) pair is archived twice, so the version name repeats.
        // Overwriting would be silent data loss.
        using var h = new Harness();

        h.Seed("Documents/note.txt", Harness.Bytes("aaaa"), Mtime);
        await h.WriteWholeAsync("Documents/note.txt", Harness.Bytes("bbbb"), Mtime + 1);
        h.Seed("Documents/note.txt", Harness.Bytes("cccc"), Mtime);
        await h.WriteWholeAsync("Documents/note.txt", Harness.Bytes("dddd"), Mtime + 2);

        var archived = h.VersionFiles();
        Assert.Equal(2, archived.Count);
        Assert.Contains(archived, name => name.Contains("-2", StringComparison.Ordinal));
    }

    // --- paths and disk space ---

    [Fact]
    public async Task APathEscapingTheDirectory_IsRejected()
    {
        using var h = new Harness();

        var outcome = await h.WriteWholeAsync("../../escape.txt", Harness.Bytes("x"), Mtime);

        Assert.Equal(ChunkStatus.PathRejected, outcome.Status);
        Assert.Equal(PathRejection.Traversal, outcome.Rejection);
    }

    [Fact]
    public async Task NoDiskSpace_StopsTheWrite()
    {
        using var h = new Harness(o => o.MinFreeBytes = long.MaxValue / 2);

        var outcome = await h.WriteWholeAsync("Documents/file.txt", Harness.Bytes("x"), Mtime);

        Assert.Equal(ChunkStatus.InsufficientStorage, outcome.Status);
        Assert.False(h.StoredExists("Documents/file.txt"));
    }

    [Fact]
    public async Task ConcurrentTransfersOfDifferentFiles_DoNotCollide()
    {
        using var h = new Harness();

        var tasks = Enumerable.Range(0, 16).Select(i =>
            h.WriteWholeAsync($"DCIM/Camera/IMG_{i:D4}.JPG", Harness.Bytes($"content {i}"), Mtime + i));

        var outcomes = await Task.WhenAll(tasks);

        Assert.All(outcomes, o => Assert.Equal(ChunkStatus.Ok, o.Status));
        Assert.All(outcomes, o => Assert.True(o.Complete));
        for (var i = 0; i < 16; i++)
            Assert.Equal(Harness.Bytes($"content {i}"), h.ReadStored($"DCIM/Camera/IMG_{i:D4}.JPG"));
    }
}
