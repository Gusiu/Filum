using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using Filum.Server;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.Logging;

namespace Filum.Core.Tests;

/// <summary>
/// The TLS versions the server accepts: TLS 1.3, with TLS 1.2 as a fallback (protocol §2).
///
/// TestServer does not speak TLS, so these tests put the server's TLS settings on a real
/// Kestrel listener and shake hands with it over a socket.
/// </summary>
public sealed class TlsVersionTests : IAsyncLifetime
{
    private readonly CertificateStore _certificates = CertificateStore.InMemory();
    private WebApplication? _app;
    private int _port;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(kestrel =>
            kestrel.Listen(IPAddress.Loopback, 0, listen =>
                listen.UseHttps(https => ServerTls.Configure(https, _certificates.Certificate))));

        _app = builder.Build();
        await _app.StartAsync();
        _port = new Uri(_app.Urls.Single()).Port;
    }

    public async Task DisposeAsync()
    {
        if (_app is not null) await _app.DisposeAsync();
        _certificates.Certificate.Dispose();
    }

    [Theory]
    [InlineData(SslProtocols.Tls13)]
    [InlineData(SslProtocols.Tls12)]
    public async Task TheVersionsOfTheProtocol_AreAccepted(SslProtocols version)
    {
        Assert.Equal(version, await HandshakeAsync(version));
    }

    // TLS 1.0 and 1.1 are obsolete in .NET as well - which is the point of the test.
#pragma warning disable SYSLIB0039
    [Theory]
    [InlineData(SslProtocols.Tls11)]
    [InlineData(SslProtocols.Tls)]
#pragma warning restore SYSLIB0039
    public async Task OlderVersions_AreRefused(SslProtocols version)
    {
        Assert.Null(await HandshakeAsync(version));
    }

    [Fact]
    public void TheVersions_AreSetByTheServerItself()
    {
        // Left to its defaults, the listener accepts whatever Windows allows, and some
        // Windows installations still allow TLS 1.0 and 1.1. On one that does not, the
        // handshake test above would pass on the system's settings alone.
        var https = new HttpsConnectionAdapterOptions();

        ServerTls.Configure(https, _certificates.Certificate);

        Assert.Equal(SslProtocols.Tls12 | SslProtocols.Tls13, https.SslProtocols);
    }

    /// <summary>The version the handshake settled on, or null when it failed.</summary>
    private async Task<SslProtocols?> HandshakeAsync(SslProtocols version)
    {
        using var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, _port);
        await using var tls = new SslStream(tcp.GetStream());

        try
        {
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "localhost",
                EnabledSslProtocols = version,
                // The certificate is self-signed; the phone trusts it by its pinned key (§3.1).
                RemoteCertificateValidationCallback = (_, _, _, _) => true,
            });
            return tls.SslProtocol;
        }
        catch (Exception e) when (e is AuthenticationException or IOException)
        {
            return null;
        }
    }
}
