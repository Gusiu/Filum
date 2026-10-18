using System.Globalization;
using System.Text;

namespace Filum.Protocol.Tests;

/// <summary>One line from docs/testvectors/paths.tsv, with its escapes decoded.</summary>
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
        var other       => throw new InvalidOperationException($"unknown rejection reason: {other}")
    };

    // Shown in the test case name; non-printing characters have to be made visible.
    public override string ToString() => Escape(Input) + " -> " + Escape(Expected);

    private static string Escape(string value)
    {
        var builder = new StringBuilder(value.Length);
        foreach (var c in value)
        {
            if (c < 0x20) builder.Append(CultureInfo.InvariantCulture, $"\\x{(int)c:X2}");
            else if (c == ' ') builder.Append('␣'); // a space has to be visible
            else builder.Append(c);
        }
        return builder.ToString();
    }
}

public static class TestVectors
{
    private const string RelativePath = "docs/testvectors/paths.tsv";

    /// <summary>The data source for [Theory]. Loaded once; the vectors are immutable.</summary>
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
                    $"{RelativePath}: expected 3 columns, got {columns.Length}: {line}");

            vectors.Add(new PathVector(Unescape(columns[0]), Unescape(columns[1]), columns[2]));
        }

        if (vectors.Count == 0)
            throw new InvalidOperationException($"{RelativePath} contains no vectors");

        return vectors;
    }

    /// <summary>
    /// Decodes the escapes described in the file's header: \xHH is a byte, \\ a literal
    /// backslash. Without them, non-printing characters and trailing spaces would not
    /// survive in a text file.
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
    /// Looks for the vector file by walking up from the binary directory. That way the same
    /// logic works regardless of build configuration and of where the tests were started.
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
            $"{RelativePath} was not found in any directory above {AppContext.BaseDirectory}");
    }
}
