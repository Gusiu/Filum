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

// --- POST /api/v1/pair (§3.3) ---

/// <summary>Zgłoszenie parowania. Certyfikat klienta w postaci DER, zakodowany base64.</summary>
public sealed record PairRequest(string Cert, string Label);

public sealed record PairResponse(string DeviceKey, string ServerInstanceId, int Protocol);

// --- GET /api/v1/admin/status ---

/// <summary>
/// Urządzenie widziane przez panel. Model jest płaski i samodzielny, bo tę samą
/// odpowiedź czyta ikona w zasobniku — osobny proces, który nie ma dostępu do
/// wewnętrznych typów serwera.
/// </summary>
public sealed record AdminDevice(
    string Pin,
    string Slug,
    string Label,
    bool Enabled,
    DateTimeOffset PairedAt,
    long FileCount,
    long BytesStored,
    DateTimeOffset? LastActivity,
    /// <summary>Liczba plików obecnych w kopii, a nieobecnych już na telefonie (§5.6).</summary>
    int OrphanCount = 0,
    long OrphanBytes = 0);

public sealed record AdminStatus(
    string ServerName,
    string Version,
    string Pin,
    IReadOnlyList<string> Endpoints,
    string BackupRoot,
    long FreeBytes,
    long TotalBytes,
    IReadOnlyList<AdminDevice> Devices);

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

// --- POST /api/v1/bundle (§5.4) ---

public sealed record BundleFileResult(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("ok")] bool Ok,
    [property: JsonPropertyName("error")] string? Error = null,
    [property: JsonPropertyName("versioned")] string? Versioned = null);

public sealed record BundleResponse(
    [property: JsonPropertyName("results")] IReadOnlyList<BundleFileResult> Results);

/// <summary>Typ zawartości paczki. Ramkowanie binarne, nie multipart (§5.4).</summary>
public static class BundleContentType
{
    public const string Value = "application/vnd.filum.bundle";
}

// --- POST /api/v1/verify (§5.5) ---

public sealed record VerifyEntry(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("d")] string Digest);

public sealed record VerifyRequest(
    [property: JsonPropertyName("files")] IReadOnlyList<VerifyEntry> Files);

public sealed record VerifyResponse(
    [property: JsonPropertyName("ok")] IReadOnlyList<string> Ok,
    [property: JsonPropertyName("mismatch")] IReadOnlyList<string> Mismatch,
    [property: JsonPropertyName("missing")] IReadOnlyList<string> Missing);

// --- GET /api/v1/orphans (§5.6) ---

/// <summary>
/// Pliki obecne na serwerze, a nieobecne już na telefonie.
///
/// Raport jest wyłącznie informacyjny — nic nie jest na jego podstawie usuwane
/// (ADR-015, niezmiennik I1).
/// </summary>
public sealed record OrphanReport(
    [property: JsonPropertyName("session")] string Session,
    [property: JsonPropertyName("generatedAt")] DateTimeOffset GeneratedAt,
    [property: JsonPropertyName("count")] int Count,
    [property: JsonPropertyName("bytes")] long Bytes,
    [property: JsonPropertyName("paths")] IReadOnlyList<string> Paths);

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
    public const string DigestRequired = "digest_required";
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
