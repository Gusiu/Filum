using System.Globalization;
using System.Reflection;
using System.Text;
using Filum.Core;
using Filum.Protocol;

namespace Filum.Server;

public static class FilumApi
{
    private static readonly TimeSpan StatsTtl = TimeSpan.FromSeconds(60);

    public static void MapFilumApi(this WebApplication app)
    {
        var api = app.MapGroup("/api/v1");

        api.MapGet("/ping", Ping);
        api.MapPost("/manifest", Manifest);
        api.MapPut("/file", File);
    }

    // --- GET /api/v1/ping (§5.1) ---

    private static IResult Ping(
        HttpContext context,
        FilumOptions options,
        StoragePaths paths,
        SpacePolicy space,
        DeviceStats stats,
        IDeviceResolver devices)
    {
        var slug = devices.Resolve(context);
        stats.RefreshIfStale(slug, StatsTtl);
        var (count, bytes) = stats.Current;

        var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

        return Results.Ok(new PingResponse(
            ProtocolVersion.Current,
            new ServerInfo(Environment.MachineName, version, ServerIdentity.InstanceId),
            LocalEndpoints.Enumerate(options.Port),
            new StorageInfo(space.FreeBytes(paths.Root), space.TotalBytes(paths.Root)),
            new DeviceInfo(devices.Label(context), count, bytes),
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
        IDeviceResolver devices)
    {
        if (request.Files is null)
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, "Brak pola files.");

        if (request.Mode is not ("full" or "delta"))
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest,
                         $"Nieznany tryb '{request.Mode}'; dozwolone: full, delta.");

        if (request.Files.Count > options.ManifestBatch)
            return Error(StatusCodes.Status413PayloadTooLarge, ErrorCodes.PayloadTooLarge,
                         $"Partia liczy {request.Files.Count} pozycji, limit to {options.ManifestBatch}.");

        var resolution = resolver.Resolve(devices.Resolve(context), request.Files);

        return Results.Ok(new ManifestResponse(resolution.Have, resolution.Needed, resolution.Rejected));
    }

    // --- PUT /api/v1/file (§5.3) ---

    private static async Task<IResult> File(
        HttpContext context,
        TransferStore transfers,
        IDeviceResolver devices,
        CancellationToken ct)
    {
        if (!TryReadChunkHeaders(context.Request, out var request, out var problem))
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, problem);

        var outcome = await transfers.WriteAsync(devices.Resolve(context), request, context.Request.Body, ct);

        return outcome.Status switch
        {
            ChunkStatus.Ok => Results.Ok(new FileChunkResponse(
                outcome.Received, outcome.Offset, outcome.Complete, outcome.VersionedPath)),

            ChunkStatus.PathRejected => Error(
                StatusCodes.Status422UnprocessableEntity,
                ErrorCodes.FromRejection(outcome.Rejection),
                "Ścieżka nie nadaje się do składowania."),

            ChunkStatus.OffsetMismatch => Error(
                StatusCodes.Status409Conflict, ErrorCodes.OffsetMismatch,
                "Przesunięcie nie zgadza się ze stanem fragmentu na serwerze.",
                outcome.ExpectedOffset),

            ChunkStatus.DigestMismatch => Error(
                StatusCodes.Status409Conflict, ErrorCodes.DigestMismatch,
                "Skrót odebranego pliku nie zgadza się z zadeklarowanym.",
                outcome.ExpectedOffset),

            ChunkStatus.TooMuchData => Error(
                StatusCodes.Status400BadRequest, ErrorCodes.BadRequest,
                "Ciało żądania jest dłuższe niż zadeklarowany rozmiar pliku.",
                outcome.ExpectedOffset),

            ChunkStatus.InsufficientStorage => Error(
                StatusCodes.Status507InsufficientStorage, ErrorCodes.InsufficientStorage,
                $"Za mało miejsca na dysku; wolne {outcome.FreeBytes} B."),

            _ => Error(StatusCodes.Status500InternalServerError, ErrorCodes.BadRequest, "Nieznany stan transferu.")
        };
    }

    // --- nagłówki ---

    private static bool TryReadChunkHeaders(HttpRequest request, out ChunkRequest chunk, out string problem)
    {
        chunk = null!;
        problem = "";

        if (!TryDecodePath(request.Headers[FilumHeaders.Path], out var path))
        {
            problem = $"{FilumHeaders.Path} musi być poprawnym base64url bez dopełnienia.";
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
                problem = $"{FilumHeaders.Digest} musi być 64 znakami szesnastkowymi (SHA-256).";
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
            problem = $"Brak nagłówka {header}.";
            value = 0;
            return false;
        }

        if (!long.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out value) || value < 0)
        {
            problem = $"{header} musi być nieujemną liczbą całkowitą, jest '{raw}'.";
            return false;
        }

        return true;
    }

    /// <summary>
    /// base64url bez dopełnienia (§5.3). Dopełnienie dokładamy sami, bo Convert go wymaga.
    /// </summary>
    private static bool TryDecodePath(string? value, out string decoded)
    {
        decoded = "";
        if (string.IsNullOrEmpty(value)) return false;

        var normalized = value.Replace('-', '+').Replace('_', '/');
        normalized = (normalized.Length % 4) switch
        {
            2 => normalized + "==",
            3 => normalized + "=",
            1 => normalized,
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

    private static IResult Error(int status, string code, string message, long? expectedOffset = null) =>
        Results.Json(new ErrorResponse(code, message, expectedOffset), statusCode: status);
}

/// <summary>Identyfikator tej instalacji, stały przez czas życia procesu (odróżnia reinstalację).</summary>
public static class ServerIdentity
{
    public static string InstanceId { get; } = Guid.NewGuid().ToString();
}
