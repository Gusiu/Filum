using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Filum.Core.Tests;

/// <summary>
/// Reading a bundle of small files (protocol §5.4, ADR-008).
///
/// The framing is binary, so the one thing that can genuinely go wrong is the boundaries:
/// slip by a byte and the next file's header gets read from the middle of the previous
/// file's content. That is what these tests aim at.
/// </summary>
public class BundleReaderTests
{
    private const long Mtime = 1_756_400_000;

    private static string Digest(byte[] content) => Convert.ToHexStringLower(SHA256.HashData(content));

    /// <summary>Builds a bundle in the form the client sends it.</summary>
    private static byte[] Bundle(params (string Path, string Content, string? Digest)[] files)
    {
        using var stream = new MemoryStream();

        foreach (var (path, content, digest) in files)
        {
            var payload = Encoding.UTF8.GetBytes(content);
            var header = JsonSerializer.SerializeToUtf8Bytes(new
            {
                p = path,
                s = (long)payload.Length,
                m = Mtime,
                d = digest ?? Digest(payload),
            });

            var length = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(length, (uint)header.Length);

            stream.Write(length);
            stream.Write(header);
            stream.Write(payload);
        }

        return stream.ToArray();
    }

    private static BundleReader Reader(Harness h) => new(h.Transfers, h.Options);

    private static Task<IReadOnlyList<BundleEntryResult>> ReadAsync(Harness h, byte[] bundle) =>
        Reader(h).ReadAsync(Harness.Slug, new MemoryStream(bundle), CancellationToken.None);

    [Fact]
    public async Task ABundleOfSeveralFiles_StoresThemAll()
    {
        using var h = new Harness();

        var results = await ReadAsync(h, Bundle(
            ("DCIM/a.jpg", "zawartosc a", null),
            ("Documents/b.txt", "zawartosc b, dluzsza", null),
            ("c.bin", "c", null)));

        Assert.Equal(3, results.Count);
        Assert.All(results, r => Assert.True(r.Ok, r.Error));
        Assert.Equal("zawartosc a", Encoding.UTF8.GetString(h.ReadStored("DCIM/a.jpg")));
        Assert.Equal("zawartosc b, dluzsza", Encoding.UTF8.GetString(h.ReadStored("Documents/b.txt")));
        Assert.Equal("c", Encoding.UTF8.GetString(h.ReadStored("c.bin")));
    }

    [Fact]
    public async Task FileBoundariesHold_ContentDoesNotLeakBetweenThem()
    {
        // A one-byte slip would show up right here: the second file would get the tail of
        // the first, and the third header would be read from the middle of the data.
        using var h = new Harness();

        await ReadAsync(h, Bundle(
            ("a", new string('A', 1000), null),
            ("b", new string('B', 1), null),
            ("c", new string('C', 777), null)));

        Assert.Equal(new string('A', 1000), Encoding.UTF8.GetString(h.ReadStored("a")));
        Assert.Equal("B", Encoding.UTF8.GetString(h.ReadStored("b")));
        Assert.Equal(new string('C', 777), Encoding.UTF8.GetString(h.ReadStored("c")));
    }

    [Fact]
    public async Task AnEmptyBundle_IsValid()
    {
        using var h = new Harness();

        Assert.Empty(await ReadAsync(h, []));
    }

    [Fact]
    public async Task ModificationTimeIsRestored()
    {
        using var h = new Harness();

        await ReadAsync(h, Bundle(("a.txt", "x", null)));

        Assert.Equal(Mtime, h.StoredMtime("a.txt"));
    }

    // --- partial success ---

    [Fact]
    public async Task AWrongDigest_RejectsOneFile_NotTheWholeBundle()
    {
        using var h = new Harness();

        var results = await ReadAsync(h, Bundle(
            ("dobry.txt", "poprawna zawartosc", null),
            ("bad.txt", "content", Digest(Encoding.UTF8.GetBytes("something else"))),
            ("kolejny.txt", "tez poprawna", null)));

        Assert.True(results[0].Ok);
        Assert.False(results[1].Ok);
        Assert.Equal("digest_mismatch", results[1].Error);
        Assert.True(results[2].Ok, "the file after the rejected one must arrive — the frames did not slip");

        Assert.True(h.StoredExists("dobry.txt"));
        Assert.False(h.StoredExists("zly.txt"));
        Assert.True(h.StoredExists("kolejny.txt"));
    }

    [Fact]
    public async Task ARejectedPath_DoesNotAbortTheBundle()
    {
        using var h = new Harness();

        var results = await ReadAsync(h, Bundle(
            ("../ucieczka.txt", "x", null),
            ("porzadny.txt", "y", null)));

        Assert.False(results[0].Ok);
        Assert.Equal("traversal", results[0].Error);
        Assert.True(results[1].Ok);
    }

    // --- invariant I2: versioning applies inside a bundle too ---

    [Fact]
    public async Task ANewVersionInsideABundle_FilesTheOldCopyAway()
    {
        using var h = new Harness();
        h.Seed("note.txt", Harness.Bytes("old version"), Mtime - 100);

        var results = await ReadAsync(h, Bundle(("note.txt", "new version", null)));

        Assert.True(results[0].Ok);
        Assert.NotNull(results[0].Versioned);
        Assert.Single(h.VersionFiles());
        Assert.Equal("new version", Encoding.UTF8.GetString(h.ReadStored("note.txt")));
    }

    // --- broken framing invalidates the whole bundle ---

    [Fact]
    public async Task ATruncatedHeader_FailsTheWholeBundle()
    {
        using var h = new Harness();

        await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, [0, 0, 1]));
    }

    [Fact]
    public async Task AnAbsurdHeaderLength_IsRejected()
    {
        using var h = new Harness();
        var bundle = new byte[8];
        BinaryPrimitives.WriteUInt32BigEndian(bundle, 500_000);

        var error = await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, bundle));
        Assert.Contains("length", error.Message);
    }

    [Fact]
    public async Task AHeaderWithoutADigest_IsRejected()
    {
        // The digest is required inside a bundle: without it a frame slip would go
        // unnoticed, because one file's content would look like another's, correctly.
        using var h = new Harness();

        var error = await Assert.ThrowsAsync<BundleFormatException>(
            () => ReadAsync(h, Bundle(("a.txt", "x", "nie-jest-skrotem"))));

        Assert.Contains("digest", error.Message);
    }

    [Fact]
    public async Task AHeaderThatIsNotJson_IsRejected()
    {
        using var h = new Harness();
        var junk = Encoding.UTF8.GetBytes("this is not json");
        var bundle = new byte[4 + junk.Length];
        BinaryPrimitives.WriteUInt32BigEndian(bundle, (uint)junk.Length);
        junk.CopyTo(bundle, 4);

        await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, bundle));
    }

    // --- limity z odpowiedzi na ping ---

    [Fact]
    public async Task ABundleOverTheFileCount_IsRejected()
    {
        using var h = new Harness(o => o.BundleMaxCount = 2);

        await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, Bundle(
            ("a", "1", null), ("b", "2", null), ("c", "3", null))));
    }

    [Fact]
    public async Task ASingleFileOverTheBundleLimit_IsRejected()
    {
        using var h = new Harness(o => o.BundleMaxBytes = 100);

        var error = await Assert.ThrowsAsync<BundleFormatException>(
            () => ReadAsync(h, Bundle(("big", new string('x', 200), null))));

        Assert.Contains("Invalid size", error.Message);
    }

    [Fact]
    public async Task FilesAddingUpOverTheLimit_AreRejected()
    {
        // Each file on its own fits; together they do not. The running total is what
        // catches this, and it had no test until the per-file guard above turned out
        // to be the one firing for both cases.
        using var h = new Harness(o => o.BundleMaxBytes = 100);

        var error = await Assert.ThrowsAsync<BundleFormatException>(
            () => ReadAsync(h, Bundle(
                ("a", new string('x', 60), null),
                ("b", new string('y', 60), null))));

        Assert.Contains("exceeds", error.Message);
    }
}
