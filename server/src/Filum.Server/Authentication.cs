using System.Net;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Filum.Core;
using Filum.Protocol;
using Microsoft.AspNetCore.Server.Kestrel.Https;

namespace Filum.Server;

/// <summary>
/// Access to the client certificate of the current connection.
///
/// Put behind an interface because TestServer does not establish real TLS - without it
/// the authorisation logic could not be tested any way other than by running the whole
/// server with a network.
/// </summary>
public interface IClientCertificateProvider
{
    X509Certificate2? Get(HttpContext context);
}

public sealed class ConnectionClientCertificateProvider : IClientCertificateProvider
{
    public X509Certificate2? Get(HttpContext context) => context.Connection.ClientCertificate;
}

/// <summary>
/// The TLS settings of the listener. Kept apart from Program.cs so the tests can put them
/// on a real Kestrel listener - TestServer does not speak TLS at all.
/// </summary>
public static class ServerTls
{
    public static void Configure(HttpsConnectionAdapterOptions https, X509Certificate2 certificate)
    {
        https.ServerCertificate = certificate;

        // Protocol §2: TLS 1.3, with TLS 1.2 as a fallback. Left at its default, the
        // listener accepts whatever Windows allows, which can still include TLS 1.0 and 1.1.
        https.SslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13;

        // AllowCertificate, not RequireCertificate: /api/v1/pair has to be reachable
        // before the device has anything to introduce itself with.
        https.ClientCertificateMode = ClientCertificateMode.AllowCertificate;

        // The trust chain is of no interest - the certificates are self-signed by
        // definition (ADR-005). The real verification is comparing the key fingerprint
        // against the list of paired devices, in DeviceAuthenticationMiddleware.
        https.ClientCertificateValidation = (_, _, _) => true;
    }
}

public static class DeviceContext
{
    private const string ItemKey = "filum.device";

    public static void Set(HttpContext context, DeviceRecord record) => context.Items[ItemKey] = record;

    /// <summary>
    /// The device established by the middleware. Throws when there is none - that would
    /// mean a resource accidentally exposed outside authentication, and failing loudly is
    /// far better than writing into an arbitrary directory.
    /// </summary>
    public static DeviceRecord Device(this HttpContext context) =>
        context.Items[ItemKey] as DeviceRecord
        ?? throw new InvalidOperationException("The request did not pass device authentication.");
}

/// <summary>
/// The access rule for the server's resources.
///
/// Three different levels apply, because they protect against three different things:
///
/// <list type="bullet">
/// <item><b>Phone</b> - mTLS with a pinned key (protocol §3.2). Protects the data from a
/// stranger's device on the same network.</item>
/// <item><b>Pairing</b> - a single-use token instead of a certificate, because the device
/// is only just obtaining one (§3.3).</item>
/// <item><b>Panel and administrative resources</b> - loopback only. The person sitting at
/// the computer is entitled by definition; exposing the panel on the network would let a
/// stranger's device mint itself a pairing token or read the contents of the backup.</item>
/// </list>
/// </summary>
public sealed class DeviceAuthenticationMiddleware(
    RequestDelegate next,
    DeviceRegistry registry,
    IClientCertificateProvider certificates,
    ILogger<DeviceAuthenticationMiddleware> logger)
{
    /// <summary>The pairing resource: a token instead of a certificate, reachable from the network.</summary>
    private const string PairPath = "/api/v1/pair";

    /// <summary>Token issuing and administrative resources - from this computer only.</summary>
    private static readonly string[] LoopbackOnlyPaths = ["/api/v1/pair/new", "/api/v1/admin"];

    public async Task InvokeAsync(HttpContext context)
    {
        var path = context.Request.Path;

        // Everything outside /api is the panel: pages, static assets, the SignalR channel.
        if (!path.StartsWithSegments("/api"))
        {
            if (!IsLoopback(context))
            {
                await Deny(context, StatusCodes.Status403Forbidden, "forbidden",
                           "The panel is reachable only from the computer running the server.");
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
                           "This resource is reachable only from the computer running the server.");
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
                       "No client certificate. Pair the device again.");
            return;
        }

        var pin = CertificateStore.ComputePin(certificate);
        var device = registry.FindByPin(pin);

        if (device is null)
        {
            logger.LogWarning("Rejected an unknown client key {Pin}", pin);
            await Deny(context, StatusCodes.Status401Unauthorized, "unauthorized",
                       "Unknown device key. Pairing again is required.");
            return;
        }

        if (!device.Enabled)
        {
            await Deny(context, StatusCodes.Status403Forbidden, "device_disabled",
                       "This device has been disabled in the server panel.");
            return;
        }

        DeviceContext.Set(context, device);
        await next(context);
    }

    /// <summary>
    /// An unknown address is treated as remote.
    ///
    /// Kestrel always supplies it, but were it ever to sit behind a proxy, a missing
    /// address must not open the panel - in doubt we take the restrictive branch.
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
