using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Filum.Core.Tests;

/// <summary>
/// Odczyt paczki małych plików (§5.4 protokołu, ADR-008).
///
/// Ramkowanie jest binarne, więc jedyne, co naprawdę może pójść nie tak, to granice:
/// przesunięcie o bajt sprawia, że nagłówek następnego pliku czytamy ze środka
/// zawartości poprzedniego. Testy celują właśnie w to.
/// </summary>
public class BundleReaderTests
{
    private const long Mtime = 1_756_400_000;

    private static string Digest(byte[] content) => Convert.ToHexStringLower(SHA256.HashData(content));

    /// <summary>Buduje paczkę w postaci, w jakiej wysyła ją klient.</summary>
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
    public async Task PaczkaZKilkomaPlikami_ZapisujeWszystkie()
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
    public async Task GranicePlikowSaZachowane_ZawartoscNieWyciekaMiedzyNimi()
    {
        // Przesunięcie o jeden bajt objawiłoby się właśnie tutaj: drugi plik dostałby
        // ogon pierwszego, a trzeci nagłówek czytalibyśmy ze środka danych.
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
    public async Task PustaPaczka_JestPoprawna()
    {
        using var h = new Harness();

        Assert.Empty(await ReadAsync(h, []));
    }

    [Fact]
    public async Task CzasModyfikacjiJestOdtwarzany()
    {
        using var h = new Harness();

        await ReadAsync(h, Bundle(("a.txt", "x", null)));

        Assert.Equal(Mtime, h.StoredMtime("a.txt"));
    }

    // --- częściowe powodzenie ---

    [Fact]
    public async Task ZlySkrot_OdrzucaJedenPlik_ANieCalaPaczke()
    {
        using var h = new Harness();

        var results = await ReadAsync(h, Bundle(
            ("dobry.txt", "poprawna zawartosc", null),
            ("zly.txt", "zawartosc", Digest(Encoding.UTF8.GetBytes("coś innego"))),
            ("kolejny.txt", "tez poprawna", null)));

        Assert.True(results[0].Ok);
        Assert.False(results[1].Ok);
        Assert.Equal("digest_mismatch", results[1].Error);
        Assert.True(results[2].Ok, "plik po odrzuconym musi dojść — ramki się nie przesunęły");

        Assert.True(h.StoredExists("dobry.txt"));
        Assert.False(h.StoredExists("zly.txt"));
        Assert.True(h.StoredExists("kolejny.txt"));
    }

    [Fact]
    public async Task OdrzuconaSciezka_NiePrzerywaPaczki()
    {
        using var h = new Harness();

        var results = await ReadAsync(h, Bundle(
            ("../ucieczka.txt", "x", null),
            ("porzadny.txt", "y", null)));

        Assert.False(results[0].Ok);
        Assert.Equal("traversal", results[0].Error);
        Assert.True(results[1].Ok);
    }

    // --- niezmiennik I2: wersjonowanie działa też w paczce ---

    [Fact]
    public async Task NowaWersjaPlikuWPaczce_OdkladaStaraKopie()
    {
        using var h = new Harness();
        h.Seed("notatka.txt", Harness.Bytes("stara wersja"), Mtime - 100);

        var results = await ReadAsync(h, Bundle(("notatka.txt", "nowa wersja", null)));

        Assert.True(results[0].Ok);
        Assert.NotNull(results[0].Versioned);
        Assert.Single(h.VersionFiles());
        Assert.Equal("nowa wersja", Encoding.UTF8.GetString(h.ReadStored("notatka.txt")));
    }

    // --- uszkodzone ramkowanie unieważnia całą paczkę ---

    [Fact]
    public async Task UrwanyNaglowek_JestBledemCalejPaczki()
    {
        using var h = new Harness();

        await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, [0, 0, 1]));
    }

    [Fact]
    public async Task NiedorzecznaDlugoscNaglowka_JestOdrzucana()
    {
        using var h = new Harness();
        var bundle = new byte[8];
        BinaryPrimitives.WriteUInt32BigEndian(bundle, 500_000);

        var error = await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, bundle));
        Assert.Contains("length", error.Message);
    }

    [Fact]
    public async Task NaglowekBezSkrotu_JestOdrzucany()
    {
        // Skrót jest w paczce wymagany: bez niego nie wykrylibyśmy przesunięcia ramek,
        // bo zawartość jednego pliku wyglądałaby jak poprawna zawartość innego.
        using var h = new Harness();

        var error = await Assert.ThrowsAsync<BundleFormatException>(
            () => ReadAsync(h, Bundle(("a.txt", "x", "nie-jest-skrotem"))));

        Assert.Contains("digest", error.Message);
    }

    [Fact]
    public async Task NaglowekNieBedacyJsonem_JestOdrzucany()
    {
        using var h = new Harness();
        var junk = Encoding.UTF8.GetBytes("to nie jest json");
        var bundle = new byte[4 + junk.Length];
        BinaryPrimitives.WriteUInt32BigEndian(bundle, (uint)junk.Length);
        junk.CopyTo(bundle, 4);

        await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, bundle));
    }

    // --- limity z odpowiedzi na ping ---

    [Fact]
    public async Task PaczkaPrzekraczajacaLiczbePlikow_JestOdrzucana()
    {
        using var h = new Harness(o => o.BundleMaxCount = 2);

        await Assert.ThrowsAsync<BundleFormatException>(() => ReadAsync(h, Bundle(
            ("a", "1", null), ("b", "2", null), ("c", "3", null))));
    }

    [Fact]
    public async Task PaczkaPrzekraczajacaRozmiar_JestOdrzucana()
    {
        using var h = new Harness(o => o.BundleMaxBytes = 100);

        var error = await Assert.ThrowsAsync<BundleFormatException>(
            () => ReadAsync(h, Bundle(("duzy", new string('x', 200), null))));

        Assert.Contains("rozmiar", error.Message);
    }
}
