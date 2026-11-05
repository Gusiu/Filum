using System.Net.Http.Json;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using Filum.Core;
using Filum.Protocol;

namespace Filum.Tray;

/// <summary>
/// The tray icon's connection to the server.
///
/// The icon is a separate process, because a Windows service runs in session 0 and cannot
/// display an interface (ADR-010). It talks to the server over the same local API the
/// panel uses - there is no special channel between them.
/// </summary>
public sealed class ServerLink : IDisposable
{
    private readonly HttpClient _http;
    private readonly string _baseUrl;

    public ServerLink(string configDirectory, int port)
    {
        _baseUrl = $"https://127.0.0.1:{port}";
        ExpectedPin = ReadServerPin(configDirectory);

        var handler = new HttpClientHandler
        {
            // The server certificate is self-signed by definition (ADR-005), so there is
            // no trust chain to check. We check the key fingerprint instead - the same
            // mechanism the phone uses, only on the desktop side.
            ServerCertificateCustomValidationCallback = ValidatePin,
        };

        _http = new HttpClient(handler)
        {
            BaseAddress = new Uri(_baseUrl),
            Timeout = TimeSpan.FromSeconds(5),
        };
    }

    /// <summary>The server's key fingerprint read from its certificate; null if the server never started.</summary>
    public string? ExpectedPin { get; }

    public string PanelUrl => _baseUrl + "/";

    public string PairingUrl => _baseUrl + "/pairing";

    public async Task<AdminStatus?> StatusAsync(CancellationToken ct = default)
    {
        try
        {
            return await _http.GetFromJsonAsync<AdminStatus>("/api/v1/admin/status", ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            // The server is stopped or has not come up yet. For the icon that is a normal
            // state, not a failure - it simply shows that nothing is running.
            return null;
        }
    }

    private bool ValidatePin(
        HttpRequestMessage request,
        X509Certificate2? certificate,
        X509Chain? chain,
        SslPolicyErrors errors)
    {
        if (certificate is null || ExpectedPin is null) return false;
        return CertificateStore.ComputePin(certificate) == ExpectedPin;
    }

    /// <summary>
    /// Reads the server's public key from its certificate. That way we do not have to trust
    /// whatever is on port 8765 - not even locally, where another process could have claimed
    /// the port first.
    ///
    /// The server publishes the certificate alone as server.cer, which the icon can read when
    /// the server runs as a service under another account. Started by hand, under the icon's
    /// own account, the server may have only server.pfx, the key itself, which the icon can
    /// read then.
    /// </summary>
    private static string? ReadServerPin(string configDirectory)
    {
        try
        {
            var published = Path.Combine(configDirectory, CertificateStore.PublishedFileName);
            if (File.Exists(published))
            {
                using var certificate = X509CertificateLoader.LoadCertificate(File.ReadAllBytes(published));
                return CertificateStore.ComputePin(certificate);
            }

            var key = Path.Combine(configDirectory, "server.pfx");
            if (!File.Exists(key)) return null;

            using var withKey = X509CertificateLoader.LoadPkcs12(
                File.ReadAllBytes(key), password: null, X509KeyStorageFlags.EphemeralKeySet);
            return CertificateStore.ComputePin(withKey);
        }
        catch (Exception)
        {
            return null;
        }
    }

    public void Dispose() => _http.Dispose();
}
