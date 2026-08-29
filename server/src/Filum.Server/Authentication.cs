using System.Security.Cryptography.X509Certificates;
using Filum.Core;
using Filum.Protocol;

namespace Filum.Server;

/// <summary>
/// Dostęp do certyfikatu klienta z bieżącego połączenia.
///
/// Wydzielone za interfejs, bo TestServer nie zestawia prawdziwego TLS — bez tego
/// logiki autoryzacji nie dałoby się przetestować inaczej niż uruchamiając cały
/// serwer z siecią.
/// </summary>
public interface IClientCertificateProvider
{
    X509Certificate2? Get(HttpContext context);
}

public sealed class ConnectionClientCertificateProvider : IClientCertificateProvider
{
    public X509Certificate2? Get(HttpContext context) => context.Connection.ClientCertificate;
}

public static class DeviceContext
{
    private const string ItemKey = "filum.device";

    public static void Set(HttpContext context, DeviceRecord record) => context.Items[ItemKey] = record;

    /// <summary>
    /// Urządzenie ustalone przez oprogramowanie pośredniczące. Rzuca, gdy zabrakło —
    /// oznaczałoby to zasób wystawiony poza uwierzytelnieniem przez pomyłkę, a wtedy
    /// głośna awaria jest znacznie lepsza niż zapis do przypadkowego katalogu.
    /// </summary>
    public static DeviceRecord Device(this HttpContext context) =>
        context.Items[ItemKey] as DeviceRecord
        ?? throw new InvalidOperationException("Żądanie nie przeszło uwierzytelnienia urządzenia.");
}

/// <summary>
/// Uwierzytelnianie po przypiętym kluczu klienta (§3.2 protokołu, ADR-005).
///
/// Protokół celowo nie ma nagłówka z identyfikatorem urządzenia — byłby podatny
/// na podszycie. Tożsamość pochodzi wyłącznie z warstwy TLS.
/// </summary>
public sealed class DeviceAuthenticationMiddleware(
    RequestDelegate next,
    DeviceRegistry registry,
    IClientCertificateProvider certificates,
    ILogger<DeviceAuthenticationMiddleware> logger)
{
    /// <summary>Jedyny zasób dostępny bez certyfikatu klienta — tam się go dopiero ustala.</summary>
    private const string PairPath = "/api/v1/pair";

    public async Task InvokeAsync(HttpContext context)
    {
        var path = context.Request.Path;

        if (!path.StartsWithSegments("/api") || path.StartsWithSegments(PairPath))
        {
            await next(context);
            return;
        }

        var certificate = certificates.Get(context);
        if (certificate is null)
        {
            await Deny(context, StatusCodes.Status401Unauthorized,
                       "Brak certyfikatu klienta. Sparuj urządzenie ponownie.");
            return;
        }

        var pin = CertificateStore.ComputePin(certificate);
        var device = registry.FindByPin(pin);

        if (device is null)
        {
            logger.LogWarning("Odrzucono nieznany klucz klienta {Pin}", pin);
            await Deny(context, StatusCodes.Status401Unauthorized,
                       "Nieznany klucz urządzenia. Wymagane ponowne parowanie.");
            return;
        }

        if (!device.Enabled)
        {
            await Deny(context, StatusCodes.Status403Forbidden,
                       "Urządzenie zostało wyłączone w panelu serwera.");
            return;
        }

        DeviceContext.Set(context, device);
        await next(context);
    }

    private static async Task Deny(HttpContext context, int status, string message)
    {
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json";
        await context.Response.WriteAsJsonAsync(new ErrorResponse(
            status == StatusCodes.Status403Forbidden ? "device_disabled" : "unauthorized", message));
    }
}
