using System.Globalization;
using System.Text;

namespace Filum.Protocol.Tests;

/// <summary>Jeden wiersz z docs/testvectors/paths.tsv, po odwróceniu sekwencji ucieczki.</summary>
public sealed record PathVector(string Input, string Expected, string Note)
{
    public bool IsRejection => Expected.StartsWith("REJECT:", StringComparison.Ordinal);

    public PathRejection ExpectedRejection => Expected["REJECT:".Length..] switch
    {
        "absolute"      => PathRejection.Absolute,
        "traversal"     => PathRejection.Traversal,
        "dot_segment"   => PathRejection.DotSegment,
        "empty_segment" => PathRejection.EmptySegment,
        "nul"           => PathRejection.Nul,
        "path_too_long" => PathRejection.PathTooLong,
        var other       => throw new InvalidOperationException($"nieznany powód odrzucenia: {other}")
    };

    // Widoczne w nazwie przypadku testowego; znaki niedrukowalne trzeba pokazać.
    public override string ToString() => Escape(Input) + " -> " + Escape(Expected);

    private static string Escape(string value)
    {
        var builder = new StringBuilder(value.Length);
        foreach (var c in value)
        {
            if (c < 0x20) builder.Append(CultureInfo.InvariantCulture, $"\\x{(int)c:X2}");
            else if (c == ' ') builder.Append('␣'); // ␣ — spacja musi być widoczna
            else builder.Append(c);
        }
        return builder.ToString();
    }
}

public static class TestVectors
{
    private const string RelativePath = "docs/testvectors/paths.tsv";

    /// <summary>Źródło danych dla [Theory]. Ładowane raz, wektory są niezmienne.</summary>
    public static IEnumerable<object[]> All() => Load().Select(v => new object[] { v });

    public static IReadOnlyList<PathVector> Load()
    {
        var file = Locate();
        var vectors = new List<PathVector>();

        foreach (var line in File.ReadAllLines(file, Encoding.UTF8))
        {
            if (line.Length == 0 || line[0] == '#') continue;

            var columns = line.Split('\t');
            if (columns.Length != 3)
                throw new InvalidOperationException(
                    $"{RelativePath}: oczekiwano 3 kolumn, jest {columns.Length}: {line}");

            vectors.Add(new PathVector(Unescape(columns[0]), Unescape(columns[1]), columns[2]));
        }

        if (vectors.Count == 0)
            throw new InvalidOperationException($"{RelativePath} nie zawiera żadnych wektorów");

        return vectors;
    }

    /// <summary>
    /// Odwraca sekwencje ucieczki z nagłówka pliku: \xHH oznacza bajt, \\ literalny backslash.
    /// Bez tego znaki niedrukowalne i końcowe spacje nie przetrwałyby w pliku tekstowym.
    /// </summary>
    public static string Unescape(string value)
    {
        var builder = new StringBuilder(value.Length);
        for (var i = 0; i < value.Length; i++)
        {
            if (value[i] == '\\' && i + 1 < value.Length)
            {
                if (value[i + 1] == '\\')
                {
                    builder.Append('\\');
                    i++;
                    continue;
                }
                if (value[i + 1] is 'x' or 'X' && i + 3 < value.Length)
                {
                    builder.Append((char)int.Parse(value.AsSpan(i + 2, 2), NumberStyles.HexNumber,
                                                   CultureInfo.InvariantCulture));
                    i += 3;
                    continue;
                }
            }
            builder.Append(value[i]);
        }
        return builder.ToString();
    }

    /// <summary>
    /// Szuka pliku wektorów, idąc w górę od katalogu binariów. Dzięki temu ta sama
    /// logika działa niezależnie od konfiguracji kompilacji i od tego, skąd uruchomiono testy.
    /// </summary>
    private static string Locate()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null)
        {
            var candidate = Path.Combine(directory.FullName, RelativePath.Replace('/', Path.DirectorySeparatorChar));
            if (File.Exists(candidate)) return candidate;
            directory = directory.Parent;
        }
        throw new FileNotFoundException(
            $"Nie znaleziono {RelativePath} w żadnym katalogu nadrzędnym względem {AppContext.BaseDirectory}");
    }
}
