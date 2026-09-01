using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// Cykl życia transferu (§5.3 i §8.2 protokołu). To jest miejsce, w którym błąd
/// oznacza uszkodzony albo utracony plik użytkownika, więc testy celują przede
/// wszystkim w ścieżki błędne, nie w scenariusz szczęśliwy.
/// </summary>
public class TransferStoreTests
{
    private const long Mtime = 1_756_400_000;

    [Fact]
    public async Task AnUpload_InOnePiece_LandsInFiles()
    {
        using var h = new Harness();
        var content = Harness.Bytes("zawartość zdjęcia");

        var outcome = await h.WriteWholeAsync("DCIM/Camera/IMG_0001.JPG", content, Mtime);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(outcome.Complete);
        Assert.Equal(content, h.ReadStored("DCIM/Camera/IMG_0001.JPG"));
        Assert.Equal(Mtime, h.StoredMtime("DCIM/Camera/IMG_0001.JPG"));
    }

    [Fact]
    public async Task DuringATransfer_TheFileNeverAppearsInFiles()
    {
        // Niezmiennik I3: użytkownik ma w każdej chwili oglądać spójne drzewo,
        // więc fragmenty nie mogą być widoczne w katalogu files/.
        using var h = new Harness();
        var content = Harness.Bytes("0123456789");

        var outcome = await h.WriteAsync("Movies/klip.mp4", content[..4], content.Length, Mtime, 0);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.False(outcome.Complete);
        Assert.False(h.StoredExists("Movies/klip.mp4"));
        Assert.Equal(4, h.PartLength("Movies/klip.mp4"));
    }

    [Fact]
    public async Task Resuming_ReassemblesTheFileCorrectly()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789ABCDEF");

        await h.WriteAsync("Movies/klip.mp4", content[..6], content.Length, Mtime, 0);
        var outcome = await h.WriteAsync("Movies/klip.mp4", content[6..], content.Length, Mtime, 6);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(outcome.Complete);
        Assert.Equal(content, h.ReadStored("Movies/klip.mp4"));
    }

    [Fact]
    public async Task Resuming_AtAWrongOffset_ReportsTheExpectedOne()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789");

        await h.WriteAsync("Movies/klip.mp4", content[..4], content.Length, Mtime, 0);
        var outcome = await h.WriteAsync("Movies/klip.mp4", content[7..], content.Length, Mtime, 7);

        Assert.Equal(ChunkStatus.OffsetMismatch, outcome.Status);
        Assert.Equal(4, outcome.ExpectedOffset);
    }

    [Fact]
    public async Task Resuming_WhenTheFileChanged_ForcesAStartFromZero()
    {
        // Scenariusz z życia: nagranie wideo było jeszcze zapisywane przy poprzedniej
        // próbie. Sklejenie starego fragmentu z nowymi bajtami dałoby plik, który
        // nigdy nie istniał — gorzej niż brak pliku, bo wygląda na poprawny.
        using var h = new Harness();
        var first = Harness.Bytes("0123456789");

        await h.WriteAsync("Movies/klip.mp4", first[..4], first.Length, Mtime, 0);
        var outcome = await h.WriteAsync("Movies/klip.mp4", first[4..], 20, Mtime + 5, 4);

        Assert.Equal(ChunkStatus.OffsetMismatch, outcome.Status);
        Assert.Equal(0, outcome.ExpectedOffset);
    }

    [Fact]
    public async Task Commit_WithACorrectDigest_Succeeds()
    {
        using var h = new Harness();
        var content = Harness.Bytes("dane do sprawdzenia");

        var outcome = await h.WriteWholeAsync("Documents/plik.txt", content, Mtime, withDigest: true);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(outcome.Complete);
    }

    [Fact]
    public async Task Commit_WithAWrongDigest_DropsThePartAndCreatesNoFile()
    {
        using var h = new Harness();
        var content = Harness.Bytes("dane do sprawdzenia");
        var wrongDigest = Harness.Digest(Harness.Bytes("coś zupełnie innego"));

        var outcome = await h.WriteAsync("Documents/plik.txt", content, content.Length, Mtime, 0, wrongDigest);

        Assert.Equal(ChunkStatus.DigestMismatch, outcome.Status);
        Assert.False(h.StoredExists("Documents/plik.txt"));
        Assert.Equal(-1, h.PartLength("Documents/plik.txt"));
    }

    [Fact]
    public async Task ExcessBytes_AreReported_NotSilentlyTruncated()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789");

        // deklarujemy 5 bajtów, wysyłamy 10
        var outcome = await h.WriteAsync("Documents/plik.txt", content, 5, Mtime, 0);

        Assert.Equal(ChunkStatus.TooMuchData, outcome.Status);
        Assert.False(h.StoredExists("Documents/plik.txt"));
    }

    // --- niezmiennik I2: nigdy nie nadpisujemy ---

    [Fact]
    public async Task ANewVersion_FilesTheOldCopyIntoVersions()
    {
        using var h = new Harness();
        var stara = Harness.Bytes("wersja pierwsza");
        var nowa = Harness.Bytes("wersja druga, dłuższa");

        h.Seed("Documents/notatka.txt", stara, Mtime);
        var outcome = await h.WriteWholeAsync("Documents/notatka.txt", nowa, Mtime + 3600);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.NotNull(outcome.VersionedPath);
        Assert.Equal(nowa, h.ReadStored("Documents/notatka.txt"));

        var archived = h.VersionFiles();
        Assert.Single(archived);

        var versionAbsolute = Path.Combine(h.Paths.DeviceRoot(Harness.Slug),
                                           outcome.VersionedPath!.Replace('/', Path.DirectorySeparatorChar));
        Assert.Equal(stara, File.ReadAllBytes(LongPath.Extended(versionAbsolute)));
    }

    [Fact]
    public async Task VersionName_ComesFromTheOldCopysMtime_NotFromUploadTime()
    {
        using var h = new Harness();
        // 2025-01-15 10:30:00 UTC
        const long staryMtime = 1_736_937_000;

        h.Seed("Documents/notatka.txt", Harness.Bytes("stara"), staryMtime);
        await h.WriteWholeAsync("Documents/notatka.txt", Harness.Bytes("nowa"), Mtime);

        var expected = DateTimeOffset.FromUnixTimeSeconds(staryMtime).UtcDateTime.ToString("yyyyMMdd'T'HHmmss'Z'");
        Assert.Equal($"notatka.{expected}.txt", h.VersionFiles().Single());
    }

    [Fact]
    public async Task TwoSuccessiveVersions_AreBothKept()
    {
        using var h = new Harness();

        h.Seed("Documents/notatka.txt", Harness.Bytes("pierwsza"), Mtime);
        await h.WriteWholeAsync("Documents/notatka.txt", Harness.Bytes("druga"), Mtime + 100);
        await h.WriteWholeAsync("Documents/notatka.txt", Harness.Bytes("trzecia"), Mtime + 200);

        Assert.Equal(2, h.VersionFiles().Count);
        Assert.Equal(Harness.Bytes("trzecia"), h.ReadStored("Documents/notatka.txt"));
    }

    [Fact]
    public async Task ReUploadingTheSameVersion_DoesNotOverwriteTheArchive()
    {
        // Ta sama para (rozmiar, mtime) trafia do archiwum dwa razy, więc nazwa wersji
        // się powtarza. Nadpisanie byłoby cichą utratą danych.
        using var h = new Harness();

        h.Seed("Documents/notatka.txt", Harness.Bytes("aaaa"), Mtime);
        await h.WriteWholeAsync("Documents/notatka.txt", Harness.Bytes("bbbb"), Mtime + 1);
        h.Seed("Documents/notatka.txt", Harness.Bytes("cccc"), Mtime);
        await h.WriteWholeAsync("Documents/notatka.txt", Harness.Bytes("dddd"), Mtime + 2);

        var archived = h.VersionFiles();
        Assert.Equal(2, archived.Count);
        Assert.Contains(archived, name => name.Contains("-2", StringComparison.Ordinal));
    }

    // --- ścieżki i miejsce na dysku ---

    [Fact]
    public async Task APathEscapingTheDirectory_IsRejected()
    {
        using var h = new Harness();

        var outcome = await h.WriteWholeAsync("../../ucieczka.txt", Harness.Bytes("x"), Mtime);

        Assert.Equal(ChunkStatus.PathRejected, outcome.Status);
        Assert.Equal(PathRejection.Traversal, outcome.Rejection);
    }

    [Fact]
    public async Task NoDiskSpace_StopsTheWrite()
    {
        using var h = new Harness(o => o.MinFreeBytes = long.MaxValue / 2);

        var outcome = await h.WriteWholeAsync("Documents/plik.txt", Harness.Bytes("x"), Mtime);

        Assert.Equal(ChunkStatus.InsufficientStorage, outcome.Status);
        Assert.False(h.StoredExists("Documents/plik.txt"));
    }

    [Fact]
    public async Task ConcurrentTransfersOfDifferentFiles_DoNotCollide()
    {
        using var h = new Harness();

        var tasks = Enumerable.Range(0, 16).Select(i =>
            h.WriteWholeAsync($"DCIM/Camera/IMG_{i:D4}.JPG", Harness.Bytes($"zawartość {i}"), Mtime + i));

        var outcomes = await Task.WhenAll(tasks);

        Assert.All(outcomes, o => Assert.Equal(ChunkStatus.Ok, o.Status));
        Assert.All(outcomes, o => Assert.True(o.Complete));
        for (var i = 0; i < 16; i++)
            Assert.Equal(Harness.Bytes($"zawartość {i}"), h.ReadStored($"DCIM/Camera/IMG_{i:D4}.JPG"));
    }
}
