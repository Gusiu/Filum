using System.Net;
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
/// Reguła dostępu do zasobów serwera.
///
/// Obowiązują trzy różne poziomy, bo chronią przed trzema różnymi rzeczami:
///
/// <list type="bullet">
/// <item><b>Telefon</b> — mTLS z przypiętym kluczem (§3.2 protokołu). Chroni dane
/// przed obcym urządzeniem w tej samej sieci.</item>
/// <item><b>Parowanie</b> — token jednorazowy zamiast certyfikatu, bo urządzenie
/// dopiero go zdobywa (§3.3).</item>
/// <item><b>Panel i zasoby administracyjne</b> — wyłącznie pętla zwrotna. Osoba
/// siedząca przy komputerze jest z definicji uprawniona; wystawienie panelu
/// w sieci pozwalałoby obcemu urządzeniu wygenerować sobie token parowania
/// albo odczytać zawartość kopii.</item>
/// </list>
/// </summary>
public sealed class DeviceAuthenticationMiddleware(
    RequestDelegate next,
    DeviceRegistry registry,
    IClientCertificateProvider certificates,
    ILogger<DeviceAuthenticationMiddleware> logger)
{
    /// <summary>Zasób parowania: token zamiast certyfikatu, dostępny z sieci.</summary>
    private const string PairPath = "/api/v1/pair";

    /// <summary>Wydawanie tokenów i zasoby administracyjne — tylko z tego komputera.</summary>
    private static readonly string[] LoopbackOnlyPaths = ["/api/v1/pair/new", "/api/v1/admin"];

    public async Task InvokeAsync(HttpContext context)
    {
        var path = context.Request.Path;

        // Wszystko poza /api to panel: strony, zasoby statyczne, kanał SignalR.
        if (!path.StartsWithSegments("/api"))
        {
            if (!IsLoopback(context))
            {
                await Deny(context, StatusCodes.Status403Forbidden, "forbidden",
                           "Panel jest dostępny wyłącznie z komputera, na którym działa serwer.");
                return;
            }

            await next(context);
            return;
        }

        if (LoopbackOnlyPaths.Any(prefix => path.StartsWithSegments(prefix)))
        {
            if (!IsLoopback(context))
            {
                await Deny(context, StatusCodes.Status403Forbidden, "forbidden",
                           "Ten zasób jest dostępny wyłącznie z komputera, na którym działa serwer.");
                return;
            }

            await next(context);
            return;
        }

        if (path.StartsWithSegments(PairPath))
        {
            await next(context);
            return;
        }

        var certificate = certificates.Get(context);
        if (certificate is null)
        {
            await Deny(context, StatusCodes.Status401Unauthorized, "unauthorized",
                       "Brak certyfikatu klienta. Sparuj urządzenie ponownie.");
            return;
        }

        var pin = CertificateStore.ComputePin(certificate);
        var device = registry.FindByPin(pin);

        if (device is null)
        {
            logger.LogWarning("Odrzucono nieznany klucz klienta {Pin}", pin);
            await Deny(context, StatusCodes.Status401Unauthorized, "unauthorized",
                       "Nieznany klucz urządzenia. Wymagane ponowne parowanie.");
            return;
        }

        if (!device.Enabled)
        {
            await Deny(context, StatusCodes.Status403Forbidden, "device_disabled",
                       "Urządzenie zostało wyłączone w panelu serwera.");
            return;
        }

        DeviceContext.Set(context, device);
        await next(context);
    }

    /// <summary>
    /// Adres nieznany traktujemy jako zdalny.
    ///
    /// Kestrel podaje go zawsze, ale gdyby kiedyś stanął za pośrednikiem, brak adresu
    /// nie może otwierać panelu — przy wątpliwości wybieramy wariant restrykcyjny.
    /// </summary>
    private static bool IsLoopback(HttpContext context)
    {
        var remote = context.Connection.RemoteIpAddress;
        return remote is not null && IPAddress.IsLoopback(remote);
    }

    private static async Task Deny(HttpContext context, int status, string code, string message)
    {
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json";
        await context.Response.WriteAsJsonAsync(new ErrorResponse(code, message));
    }
}
