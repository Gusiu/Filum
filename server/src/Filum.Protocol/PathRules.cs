using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace Filum.Protocol;

/// <summary>Why a path was rejected. Corresponds to the <c>error</c> field in an HTTP 422 response.</summary>
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

/// <summary>The result of sanitisation: either a storage path or a reason for rejection.</summary>
public readonly record struct SanitizeResult(bool Ok, string? Stored, PathRejection Reason)
{
    public static SanitizeResult Success(string stored) => new(true, stored, PathRejection.None);
    public static SanitizeResult Reject(PathRejection reason) => new(false, null, reason);
}

/// <summary>
/// Maps a canonical path from the phone onto a storage path under NTFS.
///
/// The normative implementation of docs/PATH-RULES.md. Its mirror image is the
/// <c>PathRules</c> class in the :core:protocol module on the Android side — both
/// pass the same docs/testvectors/paths.tsv set.
///
/// The class is deliberately free of file operations. Joining with the device
/// directory, the \\?\ prefix and the containment check belong to Filum.Core.
/// </summary>
public static class PathRules
{
    public const int DefaultMaxPathBytes = 4096;

    /// <summary>The NTFS limit on a single path component, in UTF-16 units.</summary>
    private const int MaxSegmentLength = 255;

    /// <summary>Target length after truncation; 247 + '~' + 6 digest characters = 254.</summary>
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
    /// Checks the canonical form. Returns <see cref="PathRejection.None"/> when the path
    /// is fit for storage.
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
    /// Turns a canonical path into a storage path. The result uses '/' as its separator;
    /// converting to the platform separator belongs to the layer above.
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
    /// Encodes a single component. The order of the rules is normative
    /// (docs/PATH-RULES.md §5) — reordering them breaks injectivity.
    /// </summary>
    public static string EncodeSegment(string segment)
    {
        ArgumentNullException.ThrowIfNull(segment);
        if (segment.Length == 0) return segment;

        // Rules 1-3. A single pass is enough: '%' reaches the output already as %25,
        // so the characters the encoding introduces are not encoded a second time.
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

        // Rule 5: a DOS reserved name. Checked against the original, because we take
        // the part before the first dot. The first character of a reserved name is
        // always a letter, so it was not encoded in the previous step and position 0
        // of the encoded form corresponds to position 0 of the original.
        var baseName = segment.Split('.')[0];
        if (ReservedNames.Contains(baseName.ToUpperInvariant()))
        {
            var escaped = new StringBuilder();
            AppendEscaped(escaped, encoded[0]);
            encoded = escaped.Append(encoded, 1, encoded.Length - 1).ToString();
        }

        // Rule 4: a trailing dot or space. We encode only the last character — the
        // earlier ones stop being trailing, so Windows will not trim them.
        var last = encoded[^1];
        if (last is '.' or ' ')
        {
            var escaped = new StringBuilder(encoded, 0, encoded.Length - 1, encoded.Length + 2);
            AppendEscaped(escaped, last);
            encoded = escaped.ToString();
        }

        // Rule 6: the component length limit.
        if (encoded.Length > MaxSegmentLength)
            encoded = TruncateAtEscapeBoundary(encoded, TruncatedSegmentLength) + "~" + ShortHash(segment);

        return encoded;
    }

    /// <summary>
    /// Reverses the percent-encoding. The <c>~xxxxxx</c> suffix from rule 6 and from
    /// case-collision resolution cannot be reversed without index.jsonl — left alone here.
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
    /// The suffix that resolves a case collision (docs/PATH-RULES.md §6).
    /// Deterministic: the same source path always yields the same suffix, including
    /// after the backup has been restored from a copy.
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
    /// Truncates without splitting a %XX sequence — otherwise the encoding would stop
    /// being reversible. Every '%' in the encoded text starts a sequence, because a
    /// literal '%' was turned into %25 earlier.
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
