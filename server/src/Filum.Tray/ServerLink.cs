using System.Net.Http.Json;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using Filum.Core;
using Filum.Protocol;

namespace Filum.Tray;

/// <summary>
/// Połączenie ikony w zasobniku z serwerem.
///
/// Ikona jest osobnym procesem, bo usługa Windows działa w sesji 0 i nie może
/// wyświetlać interfejsu (ADR-010). Rozmawia z serwerem przez to samo lokalne API,
/// z którego korzysta panel — nie ma między nimi żadnego kanału specjalnego.
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
            // Certyfikat serwera jest samopodpisany z definicji (ADR-005), więc
            // łańcucha zaufania nie ma czego sprawdzać. Sprawdzamy odcisk klucza —
            // ten sam mechanizm, którego używa telefon, tylko po stronie pulpitu.
            ServerCertificateCustomValidationCallback = ValidatePin,
        };

        _http = new HttpClient(handler)
        {
            BaseAddress = new Uri(_baseUrl),
            Timeout = TimeSpan.FromSeconds(5),
        };
    }

    /// <summary>Odcisk klucza serwera odczytany z jego certyfikatu; null, gdy serwer nigdy nie wystartował.</summary>
    public string? ExpectedPin { get; }

    public string PanelUrl => _baseUrl + "/";

    public string PairingUrl => _baseUrl + "/parowanie";

    public async Task<AdminStatus?> StatusAsync(CancellationToken ct = default)
    {
        try
        {
            return await _http.GetFromJsonAsync<AdminStatus>("/api/v1/admin/status", ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            // Serwer wyłączony albo jeszcze nie wstał. Dla ikony to normalny stan,
            // nie awaria — pokazuje wtedy po prostu, że nie działa.
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
    /// Odczytuje klucz publiczny serwera z jego certyfikatu.
    ///
    /// Ikona działa na tym samym koncie użytkownika co serwer, więc plik jest dla niej
    /// czytelny. Dzięki temu nie musimy ufać czemukolwiek na porcie 8765 — nawet
    /// lokalnie, gdzie inny proces mógłby zdążyć zająć port pierwszy.
    /// </summary>
    private static string? ReadServerPin(string configDirectory)
    {
        var path = Path.Combine(configDirectory, "server.pfx");
        if (!File.Exists(path)) return null;

        try
        {
            using var certificate = X509CertificateLoader.LoadPkcs12(
                File.ReadAllBytes(path), password: null, X509KeyStorageFlags.EphemeralKeySet);
            return CertificateStore.ComputePin(certificate);
        }
        catch (Exception)
        {
            return null;
        }
    }

    public void Dispose() => _http.Dispose();
}
