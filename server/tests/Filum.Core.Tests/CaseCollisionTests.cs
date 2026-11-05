using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// Two phone paths that differ only in case are two files on the phone and one name to NTFS
/// (docs/PATH-RULES.md §6-§7). On a case-sensitive filesystem they never meet, so the tests
/// check what has to hold on both - neither file overwrites the other, each reads back as its
/// own phone path - and, where the filesystem ignores case, the names the rules prescribe.
/// </summary>
public class CaseCollisionTests
{
    private const long Mtime = 1_756_400_000;

    [Fact]
    public async Task TwoSpellingsOfOneName_AreBothKept()
    {
        using var h = new Harness();

        await h.WriteWholeAsync("Pictures/Photo.jpg", Harness.Bytes("the first"), Mtime);
        var second = await h.WriteWholeAsync("Pictures/photo.jpg", Harness.Bytes("the second"), Mtime + 60);

        Assert.Null(second.VersionedPath);
        Assert.Empty(h.VersionFiles());
        Assert.Equal(Harness.Bytes("the first"), h.ReadStored("Pictures/Photo.jpg"));
        Assert.Equal(Harness.Bytes("the second"), h.ReadStored("Pictures/photo.jpg"));
        if (IgnoresCase(h))
        {
            // The example in PATH-RULES §6-§7, value for value.
            Assert.True(File.Exists(Path.Combine(h.Paths.FilesRoot(Harness.Slug), "Pictures", "photo~6a8a8e.jpg")));
            Assert.Equal(["{\"p\":\"Pictures/photo.jpg\",\"s\":\"Pictures/photo~6a8a8e.jpg\",\"r\":\"case_collision\"}"],
                         IndexLines(h));
        }
        else
        {
            Assert.Empty(IndexLines(h));
        }
    }

    [Fact]
    public async Task TwoSpellings_AreNotSentAgain()
    {
        // Left to share one file on NTFS, the two would take turns overwriting each other,
        // and every cycle would send both again.
        using var h = new Harness();
        await h.WriteWholeAsync("Pictures/Photo.jpg", Harness.Bytes("the first"), Mtime);
        await h.WriteWholeAsync("Pictures/photo.jpg", Harness.Bytes("the second"), Mtime + 60);

        var resolution = h.Manifest.Resolve(Harness.Slug,
        [
            new ManifestEntry("Pictures/Photo.jpg", 9, Mtime),
            new ManifestEntry("Pictures/photo.jpg", 10, Mtime + 60),
        ]);

        Assert.Equal(2, resolution.Have);
        Assert.Empty(resolution.Needed);
    }

    [Fact]
    public async Task ASecondSpelling_WithTheSameSizeAndTime_IsStillSent()
    {
        // A copy saved under another case has the same size and time. Taking the first file
        // for it would leave the second one never backed up.
        using var h = new Harness();
        await h.WriteWholeAsync("Pictures/Photo.jpg", Harness.Bytes("same bytes"), Mtime);

        var resolution = h.Manifest.Resolve(Harness.Slug, [new ManifestEntry("Pictures/photo.jpg", 10, Mtime)]);

        Assert.Equal(0, resolution.Have);
        Assert.Equal(["Pictures/photo.jpg"], resolution.Needed.Select(needed => needed.Path));
    }

    [Fact]
    public async Task TwoSpellingsSentAtOnce_AreBothKept()
    {
        // The client sends several files at a time, and each transfer has a lock of its own.
        using var h = new Harness();

        await Task.WhenAll(
            h.WriteWholeAsync("Pictures/Photo.jpg", Harness.Bytes("the first"), Mtime),
            h.WriteWholeAsync("Pictures/photo.jpg", Harness.Bytes("the second"), Mtime + 60));

        Assert.Equal(Harness.Bytes("the first"), h.ReadStored("Pictures/Photo.jpg"));
        Assert.Equal(Harness.Bytes("the second"), h.ReadStored("Pictures/photo.jpg"));
        Assert.Empty(h.VersionFiles());
    }

    [Fact]
    public async Task ADirectorySpelledDifferently_KeepsEachPathItsOwn()
    {
        // On NTFS, dcim/ goes into the DCIM/ already there; the index keeps the phone's
        // spelling, and a later DCIM/b.jpg does not take dcim/b.jpg's place.
        using var h = new Harness();
        await h.WriteWholeAsync("DCIM/a.jpg", Harness.Bytes("a"), Mtime);
        await h.WriteWholeAsync("dcim/b.jpg", Harness.Bytes("b"), Mtime);
        await h.WriteWholeAsync("DCIM/b.jpg", Harness.Bytes("another b"), Mtime);

        Assert.Equal(Harness.Bytes("b"), h.ReadStored("dcim/b.jpg"));
        Assert.Equal(Harness.Bytes("another b"), h.ReadStored("DCIM/b.jpg"));
        Assert.Empty(h.VersionFiles());
        Assert.Equal(["DCIM/a.jpg", "DCIM/b.jpg", "dcim/b.jpg"], PhonePathsOnDisk(h.Paths));
    }

    [Fact]
    public async Task ALongName_IsRecordedSoThatItReadsBack()
    {
        // PATH-RULES §5 rule 6 shortens the name, and only the index can say what it was -
        // also to a server started afresh over the same disk.
        using var h = new Harness();
        var path = "Documents/" + new string('a', 300) + ".txt";

        await h.WriteWholeAsync(path, Harness.Bytes("x"), Mtime);

        Assert.Contains("\"r\":\"truncated\"", Assert.Single(IndexLines(h)), StringComparison.Ordinal);
        Assert.Equal([path], PhonePathsOnDisk(new StoragePaths(h.Root)));
    }

    [Fact]
    public async Task PathsStoredUnderAnotherName_AreNoOrphans()
    {
        using var h = new Harness();
        string[] paths = ["Pictures/Photo.jpg", "Pictures/photo.jpg", "Documents/" + new string('a', 300) + ".txt"];
        foreach (var path in paths)
            await h.WriteWholeAsync(path, Harness.Bytes(path), Mtime);

        var reporter = new OrphanReporter(h.Paths);
        reporter.RecordBatch(Harness.Slug, "full", paths);
        var report = reporter.Complete(Harness.Slug, "full");

        Assert.Empty(report.Paths);
    }

    private static bool IgnoresCase(Harness h)
    {
        var probe = Path.Combine(h.Root, "case-probe");
        File.WriteAllText(probe, "");
        try
        {
            return File.Exists(Path.Combine(h.Root, "CASE-PROBE"));
        }
        finally
        {
            File.Delete(probe);
        }
    }

    private static IReadOnlyList<string> IndexLines(Harness h)
    {
        var file = Path.Combine(h.Paths.MetaRoot(Harness.Slug), "index.jsonl");
        return File.Exists(file) ? File.ReadAllLines(file) : [];
    }

    /// <summary>Every file under files/, read back as the phone path it stands for.</summary>
    private static IReadOnlyList<string> PhonePathsOnDisk(StoragePaths paths)
    {
        var root = paths.FilesRoot(Harness.Slug);
        return Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories)
            .Select(file => paths.PhonePathOf(Harness.Slug,
                                              Path.GetRelativePath(root, file).Replace(Path.DirectorySeparatorChar, '/')))
            .OrderBy(path => path, StringComparer.Ordinal)
            .ToList();
    }
}
