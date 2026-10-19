using System.Text;

namespace Filum.Protocol.Tests;

/// <summary>
/// Tests for the rules in docs/PATH-RULES.md. The vector set is shared with the Kotlin
/// implementation - a divergence between the two shows up here, not on a device.
/// </summary>
public class PathRulesTests
{
    [Theory]
    [MemberData(nameof(TestVectors.All), MemberType = typeof(TestVectors))]
    public void Sanitize_MatchesTheVector(PathVector vector)
    {
        var result = PathRules.Sanitize(vector.Input);

        if (vector.IsRejection)
        {
            Assert.False(result.Ok);
            Assert.Equal(vector.ExpectedRejection, result.Reason);
        }
        else
        {
            Assert.True(result.Ok, $"expected success, rejected with reason {result.Reason}");
            Assert.Equal(vector.Expected, result.Stored);
        }
    }

    [Fact]
    public void Decode_ReversesSanitize_ForEveryVector()
    {
        foreach (var vector in TestVectors.Load().Where(v => !v.IsRejection))
        {
            var stored = PathRules.Sanitize(vector.Input).Stored;
            Assert.Equal(vector.Input, PathRules.Decode(stored!));
        }
    }

    [Fact]
    public void TheVectorFileIsComplete()
    {
        // A guard against the file being silently cut short, e.g. by an editor
        // stripping trailing whitespace, or a badly resolved merge.
        Assert.True(TestVectors.Load().Count >= 30);
    }

    // --- injectivity: the reason we percent-encode at all (ADR-009) ---

    [Fact]
    public void Encoding_IsInjective_ForForbiddenCharacters()
    {
        // Replacing forbidden characters with '_' would collapse both paths into one and
        // the second file would silently overwrite the first. This is the test for exactly
        // that regression.
        var a = PathRules.Sanitize("Documents/a<b.txt").Stored;
        var b = PathRules.Sanitize("Documents/a_b.txt").Stored;

        Assert.NotEqual(a, b);
    }

    [Fact]
    public void Encoding_IsInjective_ForTextThatLooksEncoded()
    {
        // If '%' were not encoded first, both names would collide
        // after decoding.
        var a = PathRules.Sanitize("Documents/a%3Ab.txt").Stored;
        var b = PathRules.Sanitize("Documents/a:b.txt").Stored;

        Assert.NotEqual(a, b);
        Assert.Equal("Documents/a%3Ab.txt", PathRules.Decode(a!));
        Assert.Equal("Documents/a:b.txt", PathRules.Decode(b!));
    }

    [Fact]
    public void Encoding_IsInjective_AcrossTheWholeVectorSet()
    {
        var vectors = TestVectors.Load().Where(v => !v.IsRejection).ToList();
        var stored = vectors.Select(v => PathRules.Sanitize(v.Input).Stored!).ToList();

        Assert.Equal(stored.Count, stored.Distinct(StringComparer.Ordinal).Count());
    }

    // --- case collisions (§6) ---

    [Fact]
    public void TheCollisionSuffix_IsStable()
    {
        // The value is stated outright in docs/PATH-RULES.md §6 - the document and the
        // code have to agree, or the Kotlin implementation will follow the document.
        Assert.Equal("~6a8a8e", PathRules.CaseCollisionSuffix("Pictures/photo.jpg"));
    }

    [Fact]
    public void TheCollisionSuffix_DiffersByLetterCase()
    {
        Assert.NotEqual(
            PathRules.CaseCollisionSuffix("Pictures/photo.jpg"),
            PathRules.CaseCollisionSuffix("Pictures/Photo.jpg"));
    }

    // --- component length limit (§5 rule 6) ---

    [Fact]
    public void LongComponent_IsTruncatedWithASuffix()
    {
        var segment = new string('a', 300);

        var encoded = PathRules.EncodeSegment(segment);

        Assert.True(encoded.Length <= 255, $"length {encoded.Length} exceeds the NTFS limit");
        Assert.Contains("~", encoded, StringComparison.Ordinal);
    }

    [Fact]
    public void LongComponent_NeverSplitsAnEscapeSequence()
    {
        // Each character encodes to three, so the truncation boundary falls inside a %XX
        // sequence. Splitting it would destroy reversibility.
        var segment = new string(':', 200);

        var encoded = PathRules.EncodeSegment(segment);
        var body = encoded[..encoded.LastIndexOf('~')];

        Assert.Equal(0, body.Length % 3);
        Assert.DoesNotContain("%3", body[^2..], StringComparison.Ordinal);
    }

    [Fact]
    public void LongComponent_DiffersForDifferentSources()
    {
        var a = PathRules.EncodeSegment(new string('a', 300) + "x");
        var b = PathRules.EncodeSegment(new string('a', 300) + "y");

        Assert.NotEqual(a, b);
    }

    // --- individual rules, in case the vectors ever narrow their coverage ---

    [Theory]
    [InlineData("CON")]
    [InlineData("con")]
    [InlineData("CoN.txt")]
    [InlineData("LPT1.dat")]
    [InlineData("nul")]
    public void ReservedNames_HaveTheirFirstCharacterEncoded(string segment)
    {
        Assert.StartsWith("%", PathRules.EncodeSegment(segment), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("CONSOLE.txt")]
    [InlineData("COM10.log")]
    [InlineData("AUXILIARY")]
    [InlineData("comment.txt")]
    public void NamesResemblingReservedOnes_AreLeftAlone(string segment)
    {
        Assert.Equal(segment, PathRules.EncodeSegment(segment));
    }

    [Theory]
    [InlineData("file.", "file%2E")]
    [InlineData("file ", "file%20")]
    [InlineData("file..", "file.%2E")]
    [InlineData("file  ", "file %20")]
    [InlineData("a.b.c", "a.b.c")]
    public void TrailingDotsAndSpaces_EncodeOnlyTheLastCharacter(string input, string expected)
    {
        Assert.Equal(expected, PathRules.EncodeSegment(input));
    }

    [Fact]
    public void Validation_MeasuresLengthInUtf8Bytes_NotCharacters()
    {
        // 200 characters with diacritics come to 400 bytes in UTF-8.
        var path = string.Concat(Enumerable.Repeat("ą", 200));

        Assert.Equal(400, Encoding.UTF8.GetByteCount(path));
        Assert.Equal(PathRejection.None, PathRules.Validate(path, maxPathBytes: 400));
        Assert.Equal(PathRejection.PathTooLong, PathRules.Validate(path, maxPathBytes: 399));
    }
}
