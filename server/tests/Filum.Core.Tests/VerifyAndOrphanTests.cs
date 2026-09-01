using System.Security.Cryptography;
using System.Text;
using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// Kontrola integralności (§5.5 protokołu).
///
/// To jedyne miejsce, w którym serwer liczy skróty istniejących plików. Wykrywanie
/// zmian pozostaje bez skrótu (I5) — weryfikacja odpowiada na inne pytanie: czy to,
/// co leży na dysku, jest nadal tym, co przyszło z telefonu.
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
        h.Seed("DCIM/a.jpg", Harness.Bytes("zawartość"), Mtime);

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("DCIM/a.jpg", Digest("zawartość"))],
            CancellationToken.None);

        Assert.Equal(["DCIM/a.jpg"], result.Ok);
        Assert.Empty(result.Mismatch);
        Assert.Empty(result.Missing);
    }

    [Fact]
    public async Task ACorruptedByte_IsDetected()
    {
        // To jest cały powód istnienia tego trybu: nośnik potrafi po cichu przekłamać
        // zawartość, a rozmiar i mtime pozostają bez zmian, więc zwykłe wykrywanie
        // zmian tego nie zobaczy.
        using var h = new Harness();
        h.Seed("DCIM/a.jpg", Harness.Bytes("zawartość po awarii"), Mtime);

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("DCIM/a.jpg", Digest("zawartość oryginalna"))],
            CancellationToken.None);

        Assert.Equal(["DCIM/a.jpg"], result.Mismatch);
        Assert.Empty(result.Ok);
    }

    [Fact]
    public async Task MissingFile_IsReportedSeparately()
    {
        // Brak i przekłamanie znaczą co innego: pierwsze wymaga dosłania pliku,
        // drugie oznacza problem z nośnikiem.
        using var h = new Harness();

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("nie-ma-mnie.jpg", Digest("cokolwiek"))],
            CancellationToken.None);

        Assert.Equal(["nie-ma-mnie.jpg"], result.Missing);
        Assert.Empty(result.Mismatch);
    }

    [Fact]
    public async Task AnUnacceptablePath_IsTreatedAsMissing()
    {
        using var h = new Harness();

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [new VerifyEntry("../ucieczka.txt", Digest("x"))],
            CancellationToken.None);

        Assert.Equal(["../ucieczka.txt"], result.Missing);
    }

    [Fact]
    public async Task ManyPathsAtOnce_SplitIntoThreeLists()
    {
        using var h = new Harness();
        h.Seed("dobry.txt", Harness.Bytes("aaa"), Mtime);
        h.Seed("zepsuty.txt", Harness.Bytes("bbb"), Mtime);

        var result = await Service(h).VerifyAsync(
            Harness.Slug,
            [
                new VerifyEntry("dobry.txt", Digest("aaa")),
                new VerifyEntry("zepsuty.txt", Digest("coś innego")),
                new VerifyEntry("nieobecny.txt", Digest("ccc")),
            ],
            CancellationToken.None);

        Assert.Equal(["dobry.txt"], result.Ok);
        Assert.Equal(["zepsuty.txt"], result.Mismatch);
        Assert.Equal(["nieobecny.txt"], result.Missing);
    }
}

/// <summary>
/// Raport sierot (§5.6 protokołu, ADR-015).
///
/// Serwer nigdy nic nie kasuje, więc przenoszenie plików w telefonie mnoży kopie.
/// Raport daje użytkownikowi podstawę do decyzji — i na tym się kończy.
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
        h.Seed("DCIM/zostal.jpg", Harness.Bytes("aaa"), Mtime);
        h.Seed("DCIM/usuniety.jpg", Harness.Bytes("bbbbb"), Mtime);

        var report = Complete(h, "sesja-1", "DCIM/zostal.jpg");

        Assert.Equal(1, report.Count);
        Assert.Equal(["DCIM/usuniety.jpg"], report.Paths);
        Assert.Equal(5, report.Bytes);
    }

    [Fact]
    public void OrphansAreComputedFromTheMergedBatches()
    {
        // Sesja obejmująca sto tysięcy plików to pięćdziesiąt żądań; raport musi
        // widzieć sumę wszystkich, a nie tylko ostatniej partii.
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);
        h.Seed("b.txt", Harness.Bytes("b"), Mtime);
        h.Seed("c.txt", Harness.Bytes("c"), Mtime);

        var reporter = Reporter(h);
        reporter.RecordBatch(Harness.Slug, "sesja-2", ["a.txt"]);
        reporter.RecordBatch(Harness.Slug, "sesja-2", ["b.txt"]);
        var report = reporter.Complete(Harness.Slug, "sesja-2");

        Assert.Equal(["c.txt"], report.Paths);
    }

    [Fact]
    public void OrphansAreReportedInCanonicalForm()
    {
        // Na dysku plik leży pod nazwą po sanityzacji, ale telefon zna wyłącznie
        // postać kanoniczną — raport w postaci dyskowej byłby dla niego bezużyteczny.
        using var h = new Harness();
        h.Seed("Documents/raport:v2.txt", Harness.Bytes("x"), Mtime);

        var report = Complete(h, "sesja-3");

        Assert.Equal(["Documents/raport:v2.txt"], report.Paths);
    }

    [Fact]
    public void TheReportCanBeReadOnceTheSessionCloses()
    {
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);

        Complete(h, "sesja-4");
        var wczytany = Reporter(h).Read(Harness.Slug, "sesja-4");

        Assert.NotNull(wczytany);
        Assert.Equal(["a.txt"], wczytany.Paths);
    }

    [Fact]
    public void AReportForAnUnknownSession_DoesNotExist()
    {
        using var h = new Harness();

        Assert.Null(Reporter(h).Read(Harness.Slug, "nigdy-nie-bylo"));
    }

    [Fact]
    public void SessionScratchFiles_AreCleanedUpAfterwards()
    {
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);

        Complete(h, "sesja-5", "a.txt");

        var sessions = Path.Combine(h.Paths.MetaRoot(Harness.Slug), "sessions");
        Assert.True(!Directory.Exists(sessions) || Directory.GetFiles(sessions).Length == 0);
    }

    [Fact]
    public void TheReportDeletesNothing()
    {
        // Niezmiennik I1 obowiązuje także tutaj. Raport informuje, decyzja należy
        // do użytkownika i zapada poza aplikacją.
        using var h = new Harness();
        h.Seed("sierota.txt", Harness.Bytes("zawartość"), Mtime);

        Complete(h, "sesja-6");

        Assert.True(h.StoredExists("sierota.txt"));
    }

    [Fact]
    public void SessionIdentifier_CannotEscapeTheDirectory()
    {
        // Identyfikator pochodzi od klienta i trafia do nazwy pliku.
        using var h = new Harness();

        Complete(h, "../../../zlosliwa");

        var meta = h.Paths.MetaRoot(Harness.Slug);
        Assert.True(Directory.GetFiles(meta, "orphans-*.json").Length == 1);
        Assert.False(File.Exists(Path.Combine(h.Root, "zlosliwa.json")));
    }

    [Fact]
    public void TheLatestReport_IsAvailableToThePanel()
    {
        using var h = new Harness();
        h.Seed("a.txt", Harness.Bytes("a"), Mtime);

        Complete(h, "starsza");
        Complete(h, "nowsza", "a.txt");

        var latest = Reporter(h).Latest(Harness.Slug);

        Assert.NotNull(latest);
        Assert.Empty(latest.Paths);
    }
}
