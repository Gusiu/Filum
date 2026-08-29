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
    public static void MapFilumApi(this WebApplication app)
    {
        var api = app.MapGroup("/api/v1");

        api.MapPost("/pair", Pair);
        api.MapGet("/pair/new", NewPairing);

        // Zasoby administracyjne: wyłącznie z pętli zwrotnej, patrz
        // DeviceAuthenticationMiddleware. Korzysta z nich ikona w zasobniku,
        // która jest osobnym procesem i nie ma dostępu do stanu serwera inaczej.
        api.MapGet("/admin/status", (AdminService admin) => Results.Ok(admin.Snapshot()));

        api.MapGet("/ping", Ping);
        api.MapPost("/manifest", Manifest);
        api.MapPut("/file", File);
        api.MapPost("/bundle", Bundle);
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
            logger.LogWarning("Przekroczono limit prób parowania z {Source}", source);
            return Error(StatusCodes.Status429TooManyRequests, "rate_limited",
                         "Za dużo prób parowania. Spróbuj ponownie za kilka minut.");
        }

        var authorization = context.Request.Headers.Authorization.ToString();
        if (!authorization.StartsWith("Bearer ", StringComparison.Ordinal))
            return Error(StatusCodes.Status401Unauthorized, "unauthorized", "Brak tokenu parowania.");

        if (!pairing.TryConsume(authorization["Bearer ".Length..]))
            return Error(StatusCodes.Status401Unauthorized, "unauthorized",
                         "Token parowania jest nieznany, już zużyty albo wygasł.");

        X509Certificate2 certificate;
        try
        {
            certificate = X509CertificateLoader.LoadCertificate(Convert.FromBase64String(request.Cert));
        }
        catch (Exception ex) when (ex is FormatException or CryptographicException)
        {
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest,
                         "Pole cert musi zawierać certyfikat DER zakodowany base64.");
        }

        var pin = CertificateStore.ComputePin(certificate);
        var device = registry.Register(pin, CleanLabel(request.Label));

        logger.LogInformation("Sparowano urządzenie {Label} ({Slug}) o kluczu {Pin}",
                              device.Label, device.Slug, pin);

        return Results.Ok(new PairResponse(pin, ServerIdentity.InstanceId, ProtocolVersion.Current));
    }

    /// <summary>
    /// Wydaje token parowania i buduje ładunek kodu QR.
    ///
    /// Dostępne wyłącznie z pętli zwrotnej: to jedyny zasób wydający sekret, a osoba
    /// przy komputerze jest z definicji uprawniona do sparowania telefonu. Docelowo
    /// woła to panel (etap 7); na razie służy do parowania z wiersza poleceń.
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
                         "Parowanie można rozpocząć tylko z komputera, na którym działa serwer.");

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
        ManifestResolver resolver)
    {
        if (request.Files is null)
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, "Brak pola files.");

        if (request.Mode is not ("full" or "delta"))
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest,
                         $"Nieznany tryb '{request.Mode}'; dozwolone: full, delta.");

        if (request.Files.Count > options.ManifestBatch)
            return Error(StatusCodes.Status413PayloadTooLarge, ErrorCodes.PayloadTooLarge,
                         $"Partia liczy {request.Files.Count} pozycji, limit to {options.ManifestBatch}.");

        var resolution = resolver.Resolve(context.Device().Slug, request.Files);

        return Results.Ok(new ManifestResponse(resolution.Have, resolution.Needed, resolution.Rejected));
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

    // --- POST /api/v1/bundle (§5.4) ---

    private static async Task<IResult> Bundle(
        HttpContext context,
        BundleReader bundles,
        CancellationToken ct)
    {
        try
        {
            var results = await bundles.ReadAsync(context.Device().Slug, context.Request.Body, ct);

            // Paczka może się powieść częściowo: jeden plik z odrzuconą ścieżką nie
            // unieważnia pozostałych stu, które doszły poprawnie.
            return Results.Ok(new BundleResponse(
                results.Select(r => new BundleFileResult(r.Path, r.Ok, r.Error, r.Versioned)).ToList()));
        }
        catch (BundleFormatException e)
        {
            // Uszkodzone ramkowanie unieważnia całą paczkę: od tego miejsca nie wiemy
            // już, gdzie kończy się jeden plik, a zaczyna następny.
            return Error(StatusCodes.Status400BadRequest, ErrorCodes.BadRequest, e.Message);
        }
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

    /// <summary>base64url bez dopełnienia (§5.3). Dopełnienie dokładamy sami, bo Convert go wymaga.</summary>
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

    /// <summary>Etykieta jest tylko prezentacyjna (§3.2), ale trafia do nazwy katalogu — przycinamy.</summary>
    private static string CleanLabel(string? label)
    {
        var cleaned = (label ?? "").Trim();
        if (cleaned.Length == 0) return "Urządzenie";
        return cleaned.Length > 64 ? cleaned[..64] : cleaned;
    }

    private static IResult Error(int status, string code, string message, long? expectedOffset = null) =>
        Results.Json(new ErrorResponse(code, message, expectedOffset), statusCode: status);
}

/// <summary>Identyfikator tej instalacji, stały przez czas życia procesu (odróżnia reinstalację).</summary>
public static class ServerIdentity
{
    public static string InstanceId { get; } = Guid.NewGuid().ToString();
}
