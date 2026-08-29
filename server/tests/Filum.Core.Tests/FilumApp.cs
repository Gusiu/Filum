using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography.X509Certificates;
using Filum.Core;
using Filum.Protocol;
using Filum.Server;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;

namespace Filum.Core.Tests;

/// <summary>
/// Podstawiony dostęp do certyfikatu klienta. TestServer nie zestawia prawdziwego TLS,
/// więc bez tego logiki autoryzacji nie dałoby się przetestować bez uruchamiania
/// serwera z siecią.
/// </summary>
public sealed class StubClientCertificateProvider : IClientCertificateProvider
{
    public X509Certificate2? Current { get; set; }

    public X509Certificate2? Get(HttpContext context) => Current;
}

/// <summary>
/// TestServer nie ustawia adresu zdalnego, a panel i zasoby administracyjne wpuszczają
/// wyłącznie połączenia z pętli zwrotnej. Podstawiamy adres, zamiast rozluźniać warunek
/// w kodzie produkcyjnym — dzięki temu da się też sprawdzić, co widzi obce urządzenie.
/// </summary>
public sealed class RemoteAddressFilter(Func<IPAddress> address) : IStartupFilter
{
    public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next) => app =>
    {
        app.Use(async (context, following) =>
        {
            context.Connection.RemoteIpAddress = address();
            await following();
        });
        next(app);
    };
}

/// <summary>Serwer uruchomiony w pamięci, na własnym katalogu tymczasowym.</summary>
public sealed class FilumApp : WebApplicationFactory<Program>
{
    private readonly TempRoot _root = new();
    private readonly TempRoot _config = new();

    public string Root => _root.Path;

    /// <summary>Adres, z którego rzekomo przychodzą żądania. Domyślnie pętla zwrotna.</summary>
    public IPAddress RemoteAddress { get; set; } = IPAddress.Loopback;

    public StubClientCertificateProvider Certificates { get; } = new();

    /// <summary>Certyfikat klienta użyty w parowaniu — odpowiednik klucza z Android Keystore.</summary>
    public X509Certificate2 ClientCertificate { get; } = CertificateStore.InMemory().Certificate;

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseSetting("Filum:Root", _root.Path);
        builder.UseSetting("Filum:ConfigDirectory", _config.Path);
        builder.UseSetting("Filum:ManifestBatch", "3");

        // Bez tego każda instancja testowa zajmowałaby gniazda multicastu, a kilka
        // naraz wchodzi sobie w drogę. Rozgłaszanie sprawdzamy osobno, na żywym serwerze.
        builder.UseSetting("Filum:EnableMdns", "false");

        builder.ConfigureServices(services =>
        {
            services.RemoveAll<IClientCertificateProvider>();
            services.AddSingleton<IClientCertificateProvider>(Certificates);
            services.AddSingleton<IStartupFilter>(new RemoteAddressFilter(() => RemoteAddress));
        });
    }

    private readonly SemaphoreSlim _pairGate = new(1, 1);
    private string? _slug;

    /// <summary>
    /// Przechodzi pełny przebieg parowania przez HTTP i zwraca nazwę katalogu urządzenia.
    ///
    /// Wynik jest zapamiętywany, bo serwer ogranicza parowanie do 5 prób na 15 minut
    /// z jednego adresu (§3.3). Parowanie w konstruktorze każdego testu wyczerpywałoby
    /// ten limit — co zresztą wyszło przy pierwszym uruchomieniu tych testów.
    /// </summary>
    public async Task<string> PairAsync(string label = "Galaxy S24 Ultra")
    {
        await _pairGate.WaitAsync();
        try
        {
            if (_slug is not null) return _slug;

            var client = CreateClient();

            var offer = await client.GetFromJsonAsync<PairingOffer>("/api/v1/pair/new");
            var response = await PostPairAsync(client, offer!.Payload.Token, label);
            response.EnsureSuccessStatusCode();

            var paired = await response.Content.ReadFromJsonAsync<PairResponse>();
            Certificates.Current = ClientCertificate;

            _slug = DeviceRegistry.MakeSlug(label, paired!.DeviceKey);
            return _slug;
        }
        finally
        {
            _pairGate.Release();
        }
    }

    public Task<HttpResponseMessage> PostPairAsync(HttpClient client, string token, string label) =>
        PostPairAsync(client, token, label, Convert.ToBase64String(ClientCertificate.Export(X509ContentType.Cert)));

    public async Task<HttpResponseMessage> PostPairAsync(
        HttpClient client, string token, string label, string certificateBase64)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/pair")
        {
            Content = JsonContent.Create(new PairRequest(certificateBase64, label))
        };
        request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {token}");
        return await client.SendAsync(request);
    }

    public Task<PairingOffer?> NewPairingAsync() =>
        CreateClient().GetFromJsonAsync<PairingOffer>("/api/v1/pair/new");

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (!disposing) return;

        ClientCertificate.Dispose();
        _root.Dispose();
        _config.Dispose();
    }
}

public sealed record PairingOffer(string Qr, int ExpiresInSeconds, QrPayload Payload);
