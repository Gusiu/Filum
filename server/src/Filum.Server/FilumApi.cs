using System.Globalization;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Filum.Core;
using Filum.Protocol;

namespace Filum.Server;

public static class FilumApi
{
    /// <summary>
    /// The limit from §5.5. The server hashes its own copies, so the request is expensive
    /// - with no limit, a single call could occupy the disk for hours.
    /// </summary>
    private const int MaxVerifyBatch = 256;

    public static void MapFilumApi(this WebApplication app)
    {
        var api = app.MapGroup("/api/v1");

        api.MapPost("/pair", Pair);
        api.MapGet("/pair/new", NewPairing);

        // Administrative resources: loopback only, see
        // DeviceAuthenticationMiddleware. Korzysta z nich ikona w zasobniku,
        // which is a separate process and has no other access to the server's state.
        api.MapGet("/admin/status", (AdminService admin) => Results.Ok(admin.Snapshot()));

        api.MapGet("/ping", Ping);
        api.MapPost("/manifest", Manifest);
        api.MapPut("/file", File);
        api.MapPost("/bundle", Bundle);
        api.MapPost("/verify", Verify);
        api.MapGet("/orphans", Orphans);
    }

    // --- POST /api/v1/pair (§3.3) ---

    private static IResult Pair(
        HttpContext context,
        PairRequest request,
        PairingService pairing,
        PairingRateLimiter limiter,
        DeviceRegistry registry,
        ILogger<PairingService> logger)
    {
        var source = context.Connection.RemoteIpAddress?.ToString() ?? "nieznany";
        if (!limiter.TryRegisterAttempt(source))
        {
            logger.LogWarning("Pairing attempt limit exceeded from {Source}", source);
            return Error(StatusCodes.Status429TooManyRequests, "rate_limited",
                         "Too many pairing attempts. Try again in a few minutes.");
        }

        var authorization = context.Request.Headers.Authorization.ToString();
        if (!authorization.StartsWith("Bearer ", StringComparison.Ordinal))
            return Error(StatusCodes.Status401Unauthorized, "unauthorized", "No pairing token.");

        if (!pairing.TryConsume(authorization["Bearer ".Length..]))
            return Error(StatusCodes.Status401Unauthorized, "unauthorized",
                         "The pairing token is unknown, already used or expired.");

        X509Certificate2 certificate;
        try
        {
            certificate = X509CertificateLoader.LoadCertificate(Convert.FromBase64String(request.Cert));
        }
        catch (Exception ex) when (ex is FormatException or CryptographicException)
        {
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest,
                         "The cert field must carry a base64-encoded DER certificate.");
        }

        var pin = CertificateStore.ComputePin(certificate);
        var device = registry.Register(pin, CleanLabel(request.Label));

        logger.LogInformation("Paired device {Label} ({Slug}) with key {Pin}",
                              device.Label, device.Slug, pin);

        return Results.Ok(new PairResponse(pin, ServerIdentity.InstanceId, ProtocolVersion.Current));
    }

    /// <summary>
    /// Issues a pairing token and builds the QR payload.
    ///
    /// Reachable from loopback only: it is the one resource that hands out a secret, and
    /// the person at the computer is entitled by definition to pair a phone. The panel is
    /// what calls it; it also serves for pairing from a command line.
    /// </summary>
    private static IResult NewPairing(
        HttpContext context,
        FilumOptions options,
        CertificateStore certificates,
        PairingService pairing)
    {
        var remote = context.Connection.RemoteIpAddress;
        if (remote is null || !IPAddress.IsLoopback(remote))
            return Error(StatusCodes.Status403Forbidden, "forbidden",
                         "Pairing can only be started from the computer running the server.");

        var payload = new QrPayload(
            Environment.MachineName,
            certificates.Pin,
            pairing.Issue(),
            options.Port,
            LocalEndpoints.Enumerate(options.Port),
            $"filum-{certificates.Pin[..8]}");

        return Results.Ok(new
        {
            qr = PairingService.Encode(payload),
            expiresInSeconds = (int)PairingService.Lifetime.TotalSeconds,
            payload
        });
    }

    // --- GET /api/v1/ping (§5.1) ---

    private static IResult Ping(
        HttpContext context,
        FilumOptions options,
        StoragePaths paths,
        SpacePolicy space,
        DeviceStats stats)
    {
        var device = context.Device();
        var (count, bytes) = stats.For(device.Slug);

        var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

        return Results.Ok(new PingResponse(
            ProtocolVersion.Current,
            new ServerInfo(Environment.MachineName, version, ServerIdentity.InstanceId),
            LocalEndpoints.Enumerate(options.Port),
            new StorageInfo(space.FreeBytes(paths.Root), space.TotalBytes(paths.Root)),
            new DeviceInfo(device.Label, count, bytes),
            new ProtocolLimits(
                options.ManifestBatch,
                options.SmallFileThreshold,
                options.BundleMaxBytes,
                options.BundleMaxCount,
                options.MaxPathBytes),
            DateTimeOffset.UtcNow));
    }

    // --- POST /api/v1/manifest (§5.2) ---

    private static IResult Manifest(
        HttpContext context,
        ManifestRequest request,
        FilumOptions options,
        ManifestResolver resolver,
        OrphanReporter orphans)
    {
        if (request.Files is null)
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, "Brak pola files.");

        if (request.Mode is not ("full" or "delta"))
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest,
                         $"Nieznany tryb '{request.Mode}'; dozwolone: full, delta.");

        if (request.Files.Count > options.ManifestBatch)
            return Error(StatusCodes.Status413PayloadTooLarge, ErrorCodes.PayloadTooLarge,
                         $"Partia liczy {request.Files.Count} pozycji, limit to {options.ManifestBatch}.");

        var slug = context.Device().Slug;
        var resolution = resolver.Resolve(slug, request.Files);

        // Orphans are computed only for a session reporting the complete file set (§5.6).
        // An incremental session knows nothing about the files it did not mention, so the
        // difference against the disk would be meaningless.
        if (request.Mode == "full")
        {
            orphans.RecordBatch(slug, request.Session, request.Files.Select(f => f.Path));
            if (request.Final) orphans.Complete(slug, request.Session);
        }

        return Results.Ok(new ManifestResponse(
            resolution.Have,
            resolution.Needed,
            resolution.Rejected,
            // An empty list is not sent: in fast mode it would appear in every response
            // and only add traffic, with nothing for the client to read from it.
            resolution.Divergent.Count > 0 ? resolution.Divergent : null));
    }

    // --- PUT /api/v1/file (§5.3) ---

    private static async Task<IResult> File(
        HttpContext context,
        TransferStore transfers,
        CancellationToken ct)
    {
        if (!TryReadChunkHeaders(context.Request, out var request, out var problem))
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, problem);

        var outcome = await transfers.WriteAsync(context.Device().Slug, request, context.Request.Body, ct);

        return outcome.Status switch
        {
            ChunkStatus.Ok => Results.Ok(new FileChunkResponse(
                outcome.Received, outcome.Offset, outcome.Complete, outcome.VersionedPath)),

            ChunkStatus.PathRejected => Error(
                StatusCodes.Status422UnprocessableEntity,
                ErrorCodes.FromRejection(outcome.Rejection),
                "The path is unfit for storage."),

            ChunkStatus.OffsetMismatch => Error(
                StatusCodes.Status409Conflict, ErrorCodes.OffsetMismatch,
                "The offset does not match the partial file on the server.",
                outcome.ExpectedOffset),

            ChunkStatus.DigestRequired => Error(
                StatusCodes.Status400BadRequest, ErrorCodes.DigestRequired,
                "The server requires a digest on acceptance; the X-Filum-Digest header is mandatory."),

            ChunkStatus.DigestMismatch => Error(
                StatusCodes.Status409Conflict, ErrorCodes.DigestMismatch,
                "The received file's digest does not match the declared one.",
                outcome.ExpectedOffset),

            ChunkStatus.TooMuchData => Error(
                StatusCodes.Status400BadRequest, ErrorCodes.BadRequest,
                "The request body is longer than the declared file size.",
                outcome.ExpectedOffset),

            ChunkStatus.InsufficientStorage => Error(
                StatusCodes.Status507InsufficientStorage, ErrorCodes.InsufficientStorage,
                $"Not enough disk space; {outcome.FreeBytes} B free."),

            _ => Error(StatusCodes.Status500InternalServerError, ErrorCodes.BadRequest, "Nieznany stan transferu.")
        };
    }

    // --- POST /api/v1/bundle (§5.4) ---

    private static async Task<IResult> Bundle(
        HttpContext context,
        BundleReader bundles,
        CancellationToken ct)
    {
        try
        {
            var results = await bundles.ReadAsync(context.Device().Slug, context.Request.Body, ct);

            // A bundle may succeed partially: one file with a rejected path does not
            // invalidate the hundred others that arrived correctly.
            return Results.Ok(new BundleResponse(
                results.Select(r => new BundleFileResult(r.Path, r.Ok, r.Error, r.Versioned)).ToList()));
        }
        catch (BundleFormatException e)
        {
            // Broken framing invalidates the whole bundle: from that point on we no longer
            // know where one file ends and the next begins.
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, e.Message);
        }
    }

    // --- POST /api/v1/verify (§5.5) ---

    private static async Task<IResult> Verify(
        HttpContext context,
        VerifyRequest request,
        VerifyService verifier,
        CancellationToken ct)
    {
        if (request.Files is null)
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, "Brak pola files.");

        if (request.Files.Count > MaxVerifyBatch)
            return Error(StatusCodes.Status413PayloadTooLarge, ErrorCodes.PayloadTooLarge,
                         $"At most {MaxVerifyBatch} files can be checked at once.");

        return Results.Ok(await verifier.VerifyAsync(context.Device().Slug, request.Files, ct));
    }

    // --- GET /api/v1/orphans (§5.6) ---

    private static IResult Orphans(HttpContext context, string session, OrphanReporter orphans)
    {
        var report = orphans.Read(context.Device().Slug, session);

        return report is null
            ? Error(StatusCodes.Status404NotFound, "no_report",
                    "There is no report for this session. One is produced only after a session closes with a full manifest.")
            : Results.Ok(report);
    }

    // --- headers ---

    private static bool TryReadChunkHeaders(HttpRequest request, out ChunkRequest chunk, out string problem)
    {
        chunk = null!;
        problem = "";

        if (!TryDecodePath(request.Headers[FilumHeaders.Path], out var path))
        {
            problem = $"{FilumHeaders.Path} must be valid base64url without padding.";
            return false;
        }

        if (!TryReadLong(request, FilumHeaders.Size, out var size, out problem)) return false;
        if (!TryReadLong(request, FilumHeaders.Mtime, out var mtime, out problem)) return false;
        if (!TryReadLong(request, FilumHeaders.Offset, out var offset, out problem)) return false;

        if (offset > size)
        {
            problem = $"{FilumHeaders.Offset} ({offset}) przekracza {FilumHeaders.Size} ({size}).";
            return false;
        }

        string? digest = null;
        var rawDigest = request.Headers[FilumHeaders.Digest].ToString();
        if (!string.IsNullOrEmpty(rawDigest))
        {
            if (rawDigest.Length != 64 || !rawDigest.All(Uri.IsHexDigit))
            {
                problem = $"{FilumHeaders.Digest} must be 64 hexadecimal characters (SHA-256).";
                return false;
            }
            digest = rawDigest;
        }

        chunk = new ChunkRequest(path, size, mtime, offset, digest);
        return true;
    }

    private static bool TryReadLong(HttpRequest request, string header, out long value, out string problem)
    {
        problem = "";
        var raw = request.Headers[header].ToString();

        if (string.IsNullOrEmpty(raw))
        {
            problem = $"Missing header {header}.";
            value = 0;
            return false;
        }

        if (!long.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out value) || value < 0)
        {
            problem = $"{header} must be a non-negative integer, got '{raw}'.";
            return false;
        }

        return true;
    }

    /// <summary>base64url without padding (§5.3). We add the padding ourselves, since Convert needs it.</summary>
    private static bool TryDecodePath(string? value, out string decoded)
    {
        decoded = "";
        if (string.IsNullOrEmpty(value)) return false;

        var normalized = value.Replace('-', '+').Replace('_', '/');
        normalized = (normalized.Length % 4) switch
        {
            2 => normalized + "==",
            3 => normalized + "=",
            _ => normalized
        };
        if (normalized.Length % 4 != 0) return false;

        try
        {
            decoded = Encoding.UTF8.GetString(Convert.FromBase64String(normalized));
            return decoded.Length > 0;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    /// <summary>The label is presentational only (§3.2) but ends up in a directory name - so it is trimmed.</summary>
    private static string CleanLabel(string? label)
    {
        var cleaned = (label ?? "").Trim();
        if (cleaned.Length == 0) return "Device";
        return cleaned.Length > 64 ? cleaned[..64] : cleaned;
    }

    private static IResult Error(int status, string code, string message, long? expectedOffset = null) =>
        Results.Json(new ErrorResponse(code, message, expectedOffset), statusCode: status);
}

/// <summary>This installation's identifier, fixed for the process lifetime (it distinguishes a reinstall).</summary>
public static class ServerIdentity
{
    public static string InstanceId { get; } = Guid.NewGuid().ToString();
}
