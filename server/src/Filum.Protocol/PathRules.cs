using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace Filum.Protocol;

/// <summary>Powód odrzucenia ścieżki. Odpowiada polu <c>error</c> w odpowiedzi HTTP 422.</summary>
public enum PathRejection
{
    None,
    Absolute,
    Traversal,
    DotSegment,
    EmptySegment,
    Nul,
    PathTooLong
}

/// <summary>Wynik sanityzacji: albo ścieżka składowania, albo powód odrzucenia.</summary>
public readonly record struct SanitizeResult(bool Ok, string? Stored, PathRejection Reason)
{
    public static SanitizeResult Success(string stored) => new(true, stored, PathRejection.None);
    public static SanitizeResult Reject(PathRejection reason) => new(false, null, reason);
}

/// <summary>
/// Odwzorowanie ścieżki kanonicznej z telefonu na ścieżkę składowania pod NTFS.
///
/// Implementacja normatywna dla docs/PATH-RULES.md. Lustrzanym odbiciem jest
/// klasa <c>PathRules</c> w module :core:protocol po stronie Androida — obie
/// przechodzą ten sam zestaw docs/testvectors/paths.tsv.
///
/// Klasa jest celowo wolna od operacji plikowych. Złączenie z katalogiem
/// urządzenia, prefiks \\?\ i kontrola zawierania należą do Filum.Core.
/// </summary>
public static class PathRules
{
    public const int DefaultMaxPathBytes = 4096;

    /// <summary>Limit NTFS na pojedynczą składową ścieżki, w jednostkach UTF-16.</summary>
    private const int MaxSegmentLength = 255;

    /// <summary>Docelowa długość po przycięciu; 247 + '~' + 6 znaków skrótu = 254.</summary>
    private const int TruncatedSegmentLength = 247;

    private const string ForbiddenChars = "<>:\"|?*\\";

    private static readonly HashSet<string> ReservedNames = BuildReservedNames();

    private static HashSet<string> BuildReservedNames()
    {
        var names = new HashSet<string>(StringComparer.Ordinal) { "CON", "PRN", "AUX", "NUL" };
        for (var i = 1; i <= 9; i++)
        {
            names.Add($"COM{i}");
            names.Add($"LPT{i}");
        }
        return names;
    }

    /// <summary>
    /// Sprawdza postać kanoniczną. Zwraca <see cref="PathRejection.None"/>, gdy ścieżka
    /// nadaje się do składowania.
    /// </summary>
    public static PathRejection Validate(string canonicalPath, int maxPathBytes = DefaultMaxPathBytes)
    {
        ArgumentNullException.ThrowIfNull(canonicalPath);

        if (canonicalPath.Contains('\0', StringComparison.Ordinal))
            return PathRejection.Nul;

        if (canonicalPath.Length == 0)
            return PathRejection.EmptySegment;

        if (canonicalPath[0] == '/')
            return PathRejection.Absolute;

        foreach (var segment in canonicalPath.Split('/'))
        {
            if (segment.Length == 0) return PathRejection.EmptySegment;
            if (segment == ".") return PathRejection.DotSegment;
            if (segment == "..") return PathRejection.Traversal;
        }

        if (Encoding.UTF8.GetByteCount(canonicalPath) > maxPathBytes)
            return PathRejection.PathTooLong;

        return PathRejection.None;
    }

    /// <summary>
    /// Zamienia ścieżkę kanoniczną na ścieżkę składowania. Wynik używa separatora '/';
    /// zamiana na separator systemowy należy do warstwy wyżej.
    /// </summary>
    public static SanitizeResult Sanitize(string canonicalPath, int maxPathBytes = DefaultMaxPathBytes)
    {
        var rejection = Validate(canonicalPath, maxPathBytes);
        if (rejection != PathRejection.None)
            return SanitizeResult.Reject(rejection);

        var segments = canonicalPath.Split('/');
        for (var i = 0; i < segments.Length; i++)
            segments[i] = EncodeSegment(segments[i]);

        return SanitizeResult.Success(string.Join('/', segments));
    }

    /// <summary>
    /// Koduje pojedynczą składową. Kolejność reguł jest normatywna
    /// (docs/PATH-RULES.md §5) — zmiana kolejności psuje różnowartościowość.
    /// </summary>
    public static string EncodeSegment(string segment)
    {
        ArgumentNullException.ThrowIfNull(segment);
        if (segment.Length == 0) return segment;

        // Reguły 1-3. Jedno przejście wystarcza: '%' trafia do wyjścia już jako
        // %25, więc znaki wprowadzone przez kodowanie nie są kodowane ponownie.
        var builder = new StringBuilder(segment.Length + 8);
        foreach (var c in segment)
        {
            if (c == '%' || ForbiddenChars.Contains(c, StringComparison.Ordinal)
                         || c is >= '\u0001' and <= '\u001F')
            {
                AppendEscaped(builder, c);
            }
            else
            {
                builder.Append(c);
            }
        }

        var encoded = builder.ToString();

        // Reguła 5: nazwa zastrzeżona DOS. Sprawdzana na oryginale, bo bierzemy
        // człon przed pierwszą kropką. Pierwszy znak nazwy zastrzeżonej jest
        // zawsze literą, więc nie został zakodowany w poprzednim kroku i pozycja
        // 0 w wersji zakodowanej odpowiada pozycji 0 w oryginale.
        var baseName = segment.Split('.')[0];
        if (ReservedNames.Contains(baseName.ToUpperInvariant()))
        {
            var escaped = new StringBuilder();
            AppendEscaped(escaped, encoded[0]);
            encoded = escaped.Append(encoded, 1, encoded.Length - 1).ToString();
        }

        // Reguła 4: końcowa kropka lub spacja. Kodujemy wyłącznie ostatni znak —
        // wcześniejsze przestają być końcowe, więc Windows ich nie obetnie.
        var last = encoded[^1];
        if (last is '.' or ' ')
        {
            var escaped = new StringBuilder(encoded, 0, encoded.Length - 1, encoded.Length + 2);
            AppendEscaped(escaped, last);
            encoded = escaped.ToString();
        }

        // Reguła 6: limit długości składowej.
        if (encoded.Length > MaxSegmentLength)
            encoded = TruncateAtEscapeBoundary(encoded, TruncatedSegmentLength) + "~" + ShortHash(segment);

        return encoded;
    }

    /// <summary>
    /// Odwraca kodowanie procentowe. Przyrostka <c>~xxxxxx</c> z reguły 6 i z rozstrzygania
    /// kolizji wielkości liter nie da się odwrócić bez index.jsonl — ta metoda go nie tyka.
    /// </summary>
    public static string Decode(string storedPath)
    {
        ArgumentNullException.ThrowIfNull(storedPath);

        var builder = new StringBuilder(storedPath.Length);
        for (var i = 0; i < storedPath.Length; i++)
        {
            if (storedPath[i] == '%'
                && i + 2 < storedPath.Length
                && Uri.IsHexDigit(storedPath[i + 1])
                && Uri.IsHexDigit(storedPath[i + 2]))
            {
                var value = int.Parse(storedPath.AsSpan(i + 1, 2), NumberStyles.HexNumber,
                                      CultureInfo.InvariantCulture);
                builder.Append((char)value);
                i += 2;
            }
            else
            {
                builder.Append(storedPath[i]);
            }
        }
        return builder.ToString();
    }

    /// <summary>
    /// Przyrostek rozstrzygający kolizję wielkości liter (docs/PATH-RULES.md §6).
    /// Deterministyczny: ta sama ścieżka źródłowa zawsze daje ten sam przyrostek,
    /// także po odtworzeniu backupu z kopii.
    /// </summary>
    public static string CaseCollisionSuffix(string canonicalPath) => "~" + ShortHash(canonicalPath);

    private static void AppendEscaped(StringBuilder builder, char c) =>
        builder.Append('%').Append(((int)c).ToString("X2", CultureInfo.InvariantCulture));

    private static string ShortHash(string value)
    {
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexStringLower(digest)[..6];
    }

    /// <summary>
    /// Przycina tak, żeby nie rozciąć sekwencji %XX — inaczej kodowanie przestałoby
    /// być odwracalne. Każdy '%' w zakodowanym tekście rozpoczyna sekwencję, bo
    /// literalne '%' zostało wcześniej zamienione na %25.
    /// </summary>
    private static string TruncateAtEscapeBoundary(string encoded, int limit)
    {
        if (encoded.Length <= limit) return encoded;

        var cut = limit;
        for (var i = Math.Max(0, limit - 2); i < limit; i++)
        {
            if (encoded[i] == '%')
            {
                cut = i;
                break;
            }
        }
        return encoded[..cut];
    }
}
