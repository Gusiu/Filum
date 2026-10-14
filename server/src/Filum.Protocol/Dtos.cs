using System.Text.Json.Serialization;

namespace Filum.Protocol;

/// <summary>Protocol headers and constants. One place, so the literals cannot drift apart across the code.</summary>
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

/// <summary>A pairing request. The client certificate in DER form, base64-encoded.</summary>
public sealed record PairRequest(string Cert, string Label);

public sealed record PairResponse(string DeviceKey, string ServerInstanceId, int Protocol);

// --- GET /api/v1/admin/status ---

/// <summary>
/// A device as the panel sees it. The model is flat and self-contained, because the
/// tray icon reads the same response - a separate process with no access to the
/// server's internal types.
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
    /// <summary>How many files are in the copy but no longer on the phone (§5.6).</summary>
    int OrphanCount = 0,
    long OrphanBytes = 0,
    /// <summary>The device's integrity ledger state (§9.2). Null when no ledger is kept.</summary>
    AdminLedger? Ledger = null);

/// <summary>
/// A summary of one device's integrity ledger.
///
/// The entry count alone says nothing without an answer to whether the chain holds -
/// which is why both fields live here together and the panel never shows one without
/// the other.
/// </summary>
public sealed record AdminLedger(
    long Entries,
    bool Intact,
    long? BrokenAtSequence = null,
    string? Problem = null);

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
/// A manifest entry. The field names are single letters, because across a hundred
/// thousand files the cost of the names in JSON stops being negligible.
/// </summary>
public sealed record ManifestEntry(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("s")] long Size,
    [property: JsonPropertyName("m")] long Mtime,
    /// <summary>
    /// The content digest, when the client is running in thorough mode (§5.2, ADR-016).
    /// Absent in fast mode, where identity rests on metadata.
    /// </summary>
    [property: JsonPropertyName("d")] string? Digest = null);

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
/// A path rejected at the manifest stage. It lets the client mark a file as permanently
/// failed without wasting a separate PUT request just to receive a 422.
/// </summary>
public sealed record RejectedFile(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("error")] string Error);

public sealed record ManifestResponse(
    long Have,
    IReadOnlyList<NeededFile> Needed,
    IReadOnlyList<RejectedFile> Rejected,
    /// <summary>
    /// Paths for which the client reported a digest different from the one recorded in
    /// the ledger at acceptance time, despite matching size and modification time. A
    /// metadata-preserving change does not happen by accident, and the client records it
    /// in the journal.
    ///
    /// The field is **omitted** when nothing diverges - §5.2 says so. A nullable type does
    /// not achieve that on its own: System.Text.Json writes `"divergent": null` by
    /// default, which is a different thing from the field being absent.
    /// </summary>
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    [property: JsonPropertyName("divergent")] IReadOnlyList<string>? Divergent = null);

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

/// <summary>The bundle content type. Binary framing, not multipart (§5.4).</summary>
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
/// Files present on the server and no longer on the phone.
///
/// The report is informational only - nothing is deleted on its basis
/// (ADR-015, niezmiennik I1).
/// </summary>
public sealed record OrphanReport(
    [property: JsonPropertyName("session")] string Session,
    [property: JsonPropertyName("generatedAt")] DateTimeOffset GeneratedAt,
    [property: JsonPropertyName("count")] int Count,
    [property: JsonPropertyName("bytes")] long Bytes,
    [property: JsonPropertyName("paths")] IReadOnlyList<string> Paths);

// --- errors (§6) ---

public sealed record ErrorResponse(
    string Error,
    string Message,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? ExpectedOffset = null);

/// <summary>The error codes from protocol §6. The values go into the <c>error</c> field.</summary>
public static class ErrorCodes
{
    public const string BadRequest = "bad_request";
    public const string OffsetMismatch = "offset_mismatch";
    public const string DigestMismatch = "digest_mismatch";
    public const string DigestRequired = "digest_required";
    public const string PathRejected = "path_rejected";
    public const string InsufficientStorage = "insufficient_storage";
    public const string PayloadTooLarge = "payload_too_large";

    /// <summary>Maps a path rejection reason onto an <c>error</c> field value.</summary>
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
