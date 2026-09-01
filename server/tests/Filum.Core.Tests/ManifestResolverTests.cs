using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// Rozstrzyganie manifestu (§5.2). Cała odpowiedź powstaje z porównania z systemem
/// plików — serwer nie ma indeksu zawartości (ADR-003), więc te testy sprawdzają
/// przy okazji, czy dysk faktycznie wystarcza za bazę danych.
/// </summary>
public class ManifestResolverTests
{
    private const long Mtime = 1_756_400_000;

    private static ManifestEntry Entry(string path, long size, long mtime) => new(path, size, mtime);

    [Fact]
    public void AFileMatchingOnSizeAndMtime_CountsAsHeld()
    {
        using var h = new Harness();
        var content = Harness.Bytes("zawartość");
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
        var content = Harness.Bytes("zawartość");
        h.Seed("Documents/notatka.txt", content, Mtime);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Documents/notatka.txt", content.Length, Mtime + 60)]);

        Assert.Equal(0, result.Have);
        var needed = Assert.Single(result.Needed);
        Assert.Equal(0, needed.Offset);
        Assert.True(needed.Versioned);
    }

    [Fact]
    public void AFileWithADifferentSize_LandsInNeededFlaggedForVersioning()
    {
        using var h = new Harness();
        h.Seed("Documents/notatka.txt", Harness.Bytes("krótka"), Mtime);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Documents/notatka.txt", 9999, Mtime)]);

        Assert.True(Assert.Single(result.Needed).Versioned);
    }

    [Fact]
    public async Task AnUnfinishedTransfer_YieldsAResumeOffset()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789ABCDEF");
        await h.WriteAsync("Movies/klip.mp4", content[..6], content.Length, Mtime, 0);

        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Movies/klip.mp4", content.Length, Mtime)]);

        Assert.Equal(6, Assert.Single(result.Needed).Offset);
    }

    [Fact]
    public async Task PartFromADifferentVersion_IsNotUsedToResume()
    {
        using var h = new Harness();
        var content = Harness.Bytes("0123456789ABCDEF");
        await h.WriteAsync("Movies/klip.mp4", content[..6], content.Length, Mtime, 0);

        // ten sam plik, ale telefon zgłasza inny mtime — fragment jest bezużyteczny
        var result = h.Manifest.Resolve(Harness.Slug, [Entry("Movies/klip.mp4", content.Length, Mtime + 30)]);

        Assert.Equal(0, Assert.Single(result.Needed).Offset);
    }

    [Fact]
    public void AnUnacceptablePath_LandsInRejected_NotInNeeded()
    {
        // Bez tego klient dowiedziałby się o odrzuceniu dopiero z 422 przy PUT,
        // czyli po jednym zmarnowanym żądaniu na każdy taki plik.
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
        // Konsekwencja ADR-003: skoro źródłem prawdy jest dysk, to usunięcie pliku
        // w Eksploratorze musi zostać wykryte i plik dosłany.
        using var h = new Harness();
        var content = Harness.Bytes("zawartość");
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
