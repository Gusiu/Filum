using System.Text.Json.Serialization;

namespace Filum.Protocol;

/// <summary>Nagłówki i stałe protokołu. Jedno miejsce, żeby literały nie rozjechały się po kodzie.</summary>
public static class FilumHeaders
{
    public const string Path = "X-Filum-Path";
    public const string Size = "X-Filum-Size";
    public const string Mtime = "X-Filum-Mtime";
    public const string Offset = "X-Filum-Offset";
    public const string Digest = "X-Filum-Digest";
}

public static class ProtocolVersion
{
    public const int Current = 1;
}

// --- GET /api/v1/ping (§5.1) ---

public sealed record ServerInfo(string Name, string Version, string InstanceId);

public sealed record StorageInfo(long FreeBytes, long TotalBytes);

public sealed record DeviceInfo(string Label, long FileCount, long BytesStored);

public sealed record ProtocolLimits(
    int ManifestBatch,
    int SmallFileThreshold,
    int BundleMaxBytes,
    int BundleMaxCount,
    int MaxPathBytes);

public sealed record PingResponse(
    int Protocol,
    ServerInfo Server,
    IReadOnlyList<string> Endpoints,
    StorageInfo Storage,
    DeviceInfo Device,
    ProtocolLimits Limits,
    DateTimeOffset Time);

// --- POST /api/v1/manifest (§5.2) ---

/// <summary>
/// Pozycja manifestu. Nazwy pól są jednoliterowe, bo przy 100 tys. plików
/// narzut nazw w JSON przestaje być pomijalny.
/// </summary>
public sealed record ManifestEntry(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("s")] long Size,
    [property: JsonPropertyName("m")] long Mtime);

public sealed record ManifestRequest(
    string Session,
    string Mode,
    int Batch,
    bool Final,
    IReadOnlyList<ManifestEntry> Files);

public sealed record NeededFile(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("offset")] long Offset,
    [property: JsonPropertyName("versioned")] bool? Versioned = null);

/// <summary>
/// Ścieżka odrzucona na etapie manifestu. Pozwala klientowi oznaczyć plik jako
/// trwale nieudany bez marnowania osobnego żądania PUT tylko po to, żeby dostać 422.
/// </summary>
public sealed record RejectedFile(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("error")] string Error);

public sealed record ManifestResponse(
    long Have,
    IReadOnlyList<NeededFile> Needed,
    IReadOnlyList<RejectedFile> Rejected);

// --- PUT /api/v1/file (§5.3) ---

public sealed record FileChunkResponse(
    long Received,
    long Offset,
    bool Complete,
    string? Versioned = null);

// --- błędy (§6) ---

public sealed record ErrorResponse(
    string Error,
    string Message,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? ExpectedOffset = null);

/// <summary>Kody błędów z §6 protokołu. Wartości trafiają do pola <c>error</c>.</summary>
public static class ErrorCodes
{
    public const string BadRequest = "bad_request";
    public const string OffsetMismatch = "offset_mismatch";
    public const string DigestMismatch = "digest_mismatch";
    public const string PathRejected = "path_rejected";
    public const string InsufficientStorage = "insufficient_storage";
    public const string PayloadTooLarge = "payload_too_large";

    /// <summary>Mapuje powód odrzucenia ścieżki na wartość pola <c>error</c>.</summary>
    public static string FromRejection(PathRejection rejection) => rejection switch
    {
        PathRejection.Absolute => "absolute",
        PathRejection.Traversal => "traversal",
        PathRejection.DotSegment => "dot_segment",
        PathRejection.EmptySegment => "empty_segment",
        PathRejection.Nul => "nul",
        PathRejection.PathTooLong => "path_too_long",
        _ => BadRequest
    };
}
