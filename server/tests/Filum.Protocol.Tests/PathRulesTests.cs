using System.Text;

namespace Filum.Protocol.Tests;

/// <summary>
/// Testy reguł z docs/PATH-RULES.md. Zestaw wektorów jest wspólny z implementacją
/// kotlinową — rozjazd między stronami ujawni się tutaj, a nie na urządzeniu.
/// </summary>
public class PathRulesTests
{
    [Theory]
    [MemberData(nameof(TestVectors.All), MemberType = typeof(TestVectors))]
    public void Sanitize_ZgadzaSieZWektorem(PathVector vector)
    {
        var result = PathRules.Sanitize(vector.Input);

        if (vector.IsRejection)
        {
            Assert.False(result.Ok);
            Assert.Equal(vector.ExpectedRejection, result.Reason);
        }
        else
        {
            Assert.True(result.Ok, $"oczekiwano powodzenia, odrzucono z powodem {result.Reason}");
            Assert.Equal(vector.Expected, result.Stored);
        }
    }

    [Fact]
    public void Decode_OdwracaSanitize_DlaWszystkichWektorow()
    {
        foreach (var vector in TestVectors.Load().Where(v => !v.IsRejection))
        {
            var stored = PathRules.Sanitize(vector.Input).Stored;
            Assert.Equal(vector.Input, PathRules.Decode(stored!));
        }
    }

    [Fact]
    public void PlikWektorowJestKompletny()
    {
        // Zabezpieczenie przed cichym okrojeniem pliku, np. przez edytor
        // obcinający końcowe białe znaki albo błędny scalony merge.
        Assert.True(TestVectors.Load().Count >= 30);
    }

    // --- różnowartościowość: powód, dla którego kodujemy procentowo (ADR-009) ---

    [Fact]
    public void Kodowanie_JestRoznowartosciowe_DlaZnakowZabronionych()
    {
        // Podmiana znaków zabronionych na '_' sprowadziłaby obie ścieżki do jednej
        // i drugi plik cicho nadpisałby pierwszy. To jest test tej właśnie regresji.
        var a = PathRules.Sanitize("Documents/a<b.txt").Stored;
        var b = PathRules.Sanitize("Documents/a_b.txt").Stored;

        Assert.NotEqual(a, b);
    }

    [Fact]
    public void Kodowanie_JestRoznowartosciowe_DlaTekstuWygladajacegoNaZakodowany()
    {
        // Gdyby '%' nie było kodowane jako pierwsze, obie nazwy zderzyłyby się
        // po dekodowaniu.
        var a = PathRules.Sanitize("Documents/a%3Ab.txt").Stored;
        var b = PathRules.Sanitize("Documents/a:b.txt").Stored;

        Assert.NotEqual(a, b);
        Assert.Equal("Documents/a%3Ab.txt", PathRules.Decode(a!));
        Assert.Equal("Documents/a:b.txt", PathRules.Decode(b!));
    }

    [Fact]
    public void Kodowanie_JestRoznowartosciowe_NaPelnymZestawieWektorow()
    {
        var vectors = TestVectors.Load().Where(v => !v.IsRejection).ToList();
        var stored = vectors.Select(v => PathRules.Sanitize(v.Input).Stored!).ToList();

        Assert.Equal(stored.Count, stored.Distinct(StringComparer.Ordinal).Count());
    }

    // --- kolizje wielkości liter (§6) ---

    [Fact]
    public void PrzyrostekKolizji_JestStabilny()
    {
        // Wartość podana wprost w docs/PATH-RULES.md §6 — dokumentacja i kod
        // muszą się zgadzać, inaczej implementacja kotlinowa pójdzie za dokumentem.
        Assert.Equal("~420670", PathRules.CaseCollisionSuffix("Pictures/foto.jpg"));
    }

    [Fact]
    public void PrzyrostekKolizji_RozniSieDlaRoznejWielkosciLiter()
    {
        Assert.NotEqual(
            PathRules.CaseCollisionSuffix("Pictures/foto.jpg"),
            PathRules.CaseCollisionSuffix("Pictures/Foto.jpg"));
    }

    // --- limit długości składowej (§5 reguła 6) ---

    [Fact]
    public void DlugaSkladowa_JestPrzycinanaZPrzyrostkiem()
    {
        var segment = new string('a', 300);

        var encoded = PathRules.EncodeSegment(segment);

        Assert.True(encoded.Length <= 255, $"długość {encoded.Length} przekracza limit NTFS");
        Assert.Contains("~", encoded, StringComparison.Ordinal);
    }

    [Fact]
    public void DlugaSkladowa_NieRozcinaSekwencjiUcieczki()
    {
        // Każdy znak koduje się na 3 znaki, więc granica przycięcia wypada
        // w środku sekwencji %XX. Rozcięcie zabiłoby odwracalność.
        var segment = new string(':', 200);

        var encoded = PathRules.EncodeSegment(segment);
        var body = encoded[..encoded.LastIndexOf('~')];

        Assert.Equal(0, body.Length % 3);
        Assert.DoesNotContain("%3", body[^2..], StringComparison.Ordinal);
    }

    [Fact]
    public void DlugaSkladowa_RozniSieDlaRoznychZrodel()
    {
        var a = PathRules.EncodeSegment(new string('a', 300) + "x");
        var b = PathRules.EncodeSegment(new string('a', 300) + "y");

        Assert.NotEqual(a, b);
    }

    // --- pojedyncze reguły, gdyby wektory kiedyś zawęziły zakres ---

    [Theory]
    [InlineData("CON")]
    [InlineData("con")]
    [InlineData("CoN.txt")]
    [InlineData("LPT1.dat")]
    [InlineData("nul")]
    public void NazwyZastrzezone_MajaZakodowanyPierwszyZnak(string segment)
    {
        Assert.StartsWith("%", PathRules.EncodeSegment(segment), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("CONSOLE.txt")]
    [InlineData("COM10.log")]
    [InlineData("AUXILIARY")]
    [InlineData("comment.txt")]
    public void NazwyPodobneDoZastrzezonych_ZostajaBezZmian(string segment)
    {
        Assert.Equal(segment, PathRules.EncodeSegment(segment));
    }

    [Theory]
    [InlineData("plik.", "plik%2E")]
    [InlineData("plik ", "plik%20")]
    [InlineData("plik..", "plik.%2E")]
    [InlineData("plik  ", "plik %20")]
    [InlineData("a.b.c", "a.b.c")]
    public void KoncoweKropkiISpacje_KodujaTylkoOstatniZnak(string input, string expected)
    {
        Assert.Equal(expected, PathRules.EncodeSegment(input));
    }

    [Fact]
    public void Walidacja_LiczyDlugoscWBajtachUtf8_NieWZnakach()
    {
        // 200 znaków z polskimi diakrytykami to 400 bajtów w UTF-8.
        var path = string.Concat(Enumerable.Repeat("ą", 200));

        Assert.Equal(400, Encoding.UTF8.GetByteCount(path));
        Assert.Equal(PathRejection.None, PathRules.Validate(path, maxPathBytes: 400));
        Assert.Equal(PathRejection.PathTooLong, PathRules.Validate(path, maxPathBytes: 399));
    }
}
