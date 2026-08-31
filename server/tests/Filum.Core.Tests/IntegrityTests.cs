using System.Security.Cryptography;
using System.Text;

namespace Filum.Core.Tests;

/// <summary>
/// Rejestr integralności (ADR-016).
///
/// Rejestr odpowiada na pytanie, którego nie da się zadać samej kopii plików: co
/// i kiedy serwer przyjął, i czy od tamtej pory ktoś to ruszył. Łańcuch skrótów
/// zamienia listę wpisów w zapis, którego nie da się po cichu poprawić — i to jest
/// jedyna właściwość, dla której ten rejestr istnieje, więc testy celują w nią.
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
    public void PierwszyWpis_ZaczynaLancuchOdKotwicy()
    {
        using var h = new Harness();

        var entry = h.Ledger.Append(Harness.Slug, "a.txt", 10, Mtime, Digest("aaa"));

        Assert.Equal(1, entry.Sequence);
        Assert.Equal(IntegrityLedger.Genesis, entry.Previous);
    }

    [Fact]
    public void KazdyWpis_WskazujeNaPoprzedni()
    {
        using var h = new Harness();

        Append(h, "a.txt", "aaa");
        var second = Append(h, "b.txt", "bbb");

        Assert.Equal(2, second.Sequence);
        Assert.NotEqual(IntegrityLedger.Genesis, second.Previous);
    }

    [Fact]
    public void NietkniętyRejestr_JestSpojny()
    {
        using var h = new Harness();
        for (var i = 1; i <= 20; i++) Append(h, $"plik{i}.txt", $"zawartość {i}");

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.True(result.Intact, result.Problem);
        Assert.Equal(20, result.Entries);
        Assert.Null(result.BrokenAtSequence);
    }

    [Fact]
    public void PustyRejestr_JestSpojny()
    {
        using var h = new Harness();

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.True(result.Intact);
        Assert.Equal(0, result.Entries);
    }

    // --- właściwość, dla której rejestr w ogóle istnieje ---

    [Fact]
    public void PodmianaWpisu_JestWykrywana()
    {
        // Ktoś z dostępem do dysku zmienia skrót w rejestrze, żeby podmieniony plik
        // wyglądał na oryginalny. Wpis sam w sobie wygląda poprawnie — niezgodność
        // ujawnia dopiero łańcuch, bo następny wiersz wskazuje na starą postać.
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Append(h, "b.txt", "bbb");
        Append(h, "c.txt", "ccc");

        var lines = File.ReadAllLines(LedgerPath(h));
        lines[1] = lines[1].Replace(Digest("bbb"), Digest("podmienione"));
        File.WriteAllLines(LedgerPath(h), lines);

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.False(result.Intact);
        Assert.Equal(3, result.BrokenAtSequence);
    }

    [Fact]
    public void UsuniecieWpisu_JestWykrywane()
    {
        // Usunięcie wiersza to najprostszy sposób, żeby plik „nigdy nie istniał".
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Append(h, "kompromitujacy.txt", "bbb");
        Append(h, "c.txt", "ccc");

        var lines = File.ReadAllLines(LedgerPath(h)).ToList();
        lines.RemoveAt(1);
        File.WriteAllLines(LedgerPath(h), lines);

        var result = h.Ledger.Verify(Harness.Slug);

        Assert.False(result.Intact);
        Assert.Contains("brakuje", result.Problem, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void DopisanieWpisuWSrodku_JestWykrywane()
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
    public void DopisanieNaKoncu_JestPoprawne()
    {
        // Rejestr jest dopisywany z definicji — nowe wpisy nie mogą go unieważniać.
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);

        Append(h, "b.txt", "bbb");
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);
    }

    [Fact]
    public void UszkodzonyWiersz_JestWykrywany()
    {
        using var h = new Harness();
        Append(h, "a.txt", "aaa");
        File.AppendAllText(LedgerPath(h), "to nie jest json\n");

        Assert.False(h.Ledger.Verify(Harness.Slug).Intact);
    }

    [Fact]
    public void OstatniSkrotPliku_PochodziZNajnowszegoWpisu()
    {
        // Plik może trafić do rejestru wiele razy — liczy się stan bieżący.
        using var h = new Harness();
        Append(h, "notatka.txt", "wersja pierwsza");
        Append(h, "notatka.txt", "wersja druga");

        Assert.Equal(Digest("wersja druga"), h.Ledger.DigestOf(Harness.Slug, "notatka.txt"));
    }

    [Fact]
    public void NieznanyPlik_NieMaSkrotu()
    {
        using var h = new Harness();

        Assert.Null(h.Ledger.DigestOf(Harness.Slug, "nigdy-nie-byl.txt"));
    }
}

/// <summary>Wymóg skrótu przy przyjęciu pliku (ADR-016).</summary>
public class DigestRequirementTests
{
    private const long Mtime = 1_756_400_000;

    [Fact]
    public async Task PrzyWlaczonymWymogu_PlikBezSkrotuJestOdrzucany()
    {
        using var h = new Harness(o => o.RequireDigest = true);

        var outcome = await h.WriteWholeAsync("a.txt", Harness.Bytes("zawartość"), Mtime, withDigest: false);

        Assert.Equal(ChunkStatus.DigestRequired, outcome.Status);
        Assert.False(h.StoredExists("a.txt"));
    }

    [Fact]
    public async Task PrzyWlaczonymWymogu_PlikZeSkrotemPrzechodzi()
    {
        using var h = new Harness(o => o.RequireDigest = true);

        var outcome = await h.WriteWholeAsync("a.txt", Harness.Bytes("zawartość"), Mtime, withDigest: true);

        Assert.Equal(ChunkStatus.Ok, outcome.Status);
        Assert.True(h.StoredExists("a.txt"));
    }

    [Fact]
    public async Task OdrzuceniePrzedZapisem_NieZostawiaFragmentu()
    {
        // Ważne, żeby odmowa nastąpiła zanim cokolwiek trafi na dysk: inaczej
        // każde żądanie bez skrótu zostawiałoby śmieci w katalogu przejściowym.
        using var h = new Harness(o => o.RequireDigest = true);

        await h.WriteWholeAsync("a.txt", Harness.Bytes("zawartość"), Mtime, withDigest: false);

        Assert.Equal(-1, h.PartLength("a.txt"));
    }

    [Fact]
    public async Task PrzyjetyPlik_TrafiaDoRejestru()
    {
        using var h = new Harness(o => o.RequireDigest = true);
        var content = Harness.Bytes("zawartość do rejestru");

        await h.WriteWholeAsync("DCIM/a.jpg", content, Mtime, withDigest: true);

        var entry = h.Ledger.Read(Harness.Slug).Single();
        Assert.Equal("DCIM/a.jpg", entry.Path);
        Assert.Equal(content.Length, entry.Size);
        Assert.Equal(Mtime, entry.Mtime);
        Assert.Equal(Harness.Digest(content), entry.Digest);
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);
    }

    [Fact]
    public async Task NieudanyTransfer_NieZostawiaWpisu()
    {
        // Wpis o pliku, który ostatecznie nie wylądował na dysku, byłby gorszy
        // niż brak wpisu — sugerowałby posiadanie czegoś, czego nie ma.
        using var h = new Harness(o => o.RequireDigest = true);
        var wrongDigest = Harness.Digest(Harness.Bytes("coś zupełnie innego"));

        await h.WriteAsync("a.txt", Harness.Bytes("zawartość"), 9, Mtime, 0, wrongDigest);

        Assert.Empty(h.Ledger.Read(Harness.Slug));
    }

    [Fact]
    public async Task KolejneWersjeTegoSamegoPliku_ZostawiajaSladObu()
    {
        // Rejestr jest zapisem przyjęć, nie stanem bieżącym. Poprzednia wersja pliku
        // leży w .filum/versions, a jej skrót zostaje w rejestrze — razem dają
        // odtwarzalną historię, a nie tylko migawkę.
        using var h = new Harness(o => o.RequireDigest = true);

        await h.WriteWholeAsync("notatka.txt", Harness.Bytes("pierwsza"), Mtime, withDigest: true);
        await h.WriteWholeAsync("notatka.txt", Harness.Bytes("druga"), Mtime + 100, withDigest: true);

        var entries = h.Ledger.Read(Harness.Slug).ToList();
        Assert.Equal(2, entries.Count);
        Assert.Equal(Harness.Digest(Harness.Bytes("pierwsza")), entries[0].Digest);
        Assert.Equal(Harness.Digest(Harness.Bytes("druga")), entries[1].Digest);
        Assert.True(h.Ledger.Verify(Harness.Slug).Intact);
    }
}
