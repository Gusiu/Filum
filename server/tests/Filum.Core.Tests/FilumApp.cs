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
/// A stubbed way of reaching the client certificate. TestServer does not establish real
/// TLS, so without this the authorisation logic could not be tested without running the
/// server with a network.
/// </summary>
public sealed class StubClientCertificateProvider : IClientCertificateProvider
{
    public X509Certificate2? Current { get; set; }

    public X509Certificate2? Get(HttpContext context) => Current;
}

/// <summary>
/// TestServer sets no remote address, while the panel and the administrative resources
/// admit loopback connections only. We substitute the address rather than loosening the
/// condition in production code - which also lets us check what a stranger's device sees.
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

/// <summary>The server running in memory, on a temporary directory of its own.</summary>
public sealed class FilumApp : WebApplicationFactory<Program>
{
    private readonly TempRoot _root = new();
    private readonly TempRoot _config = new();

    public string Root => _root.Path;

    /// <summary>The address requests supposedly come from. Loopback by default.</summary>
    public IPAddress RemoteAddress { get; set; } = IPAddress.Loopback;

    public StubClientCertificateProvider Certificates { get; } = new();

    /// <summary>The client certificate used in pairing - the counterpart of the Android Keystore key.</summary>
    public X509Certificate2 ClientCertificate { get; } = CertificateStore.InMemory().Certificate;

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseSetting("Filum:Root", _root.Path);
        builder.UseSetting("Filum:ConfigDirectory", _config.Path);
        builder.UseSetting("Filum:ManifestBatch", "3");

        // Without this every test instance would claim multicast sockets, and several at
        // once get in each other's way. Advertising is tested separately, on a live server.
        builder.UseSetting("Filum:EnableMdns", "false");

        // The HTTP resource tests check header parsing and response codes; the integrity
        // policy has a suite of its own, which turns the requirement on explicitly.
        builder.UseSetting("Filum:RequireDigest", "false");

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
    /// Runs the full pairing exchange over HTTP and returns the device directory name.
    ///
    /// The result is remembered, because the server limits pairing to 5 attempts per 15
    /// minutes from one address (§3.3). Pairing in every test's constructor would exhaust
    /// that limit - as it duly did the first time these tests were run.
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
