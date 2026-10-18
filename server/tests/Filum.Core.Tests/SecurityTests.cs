using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Filum.Protocol;
using Filum.Server;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Time.Testing;

namespace Filum.Core.Tests;

/// <summary>The server certificate and key fingerprints (protocol §3.1, ADR-005).</summary>
public class CertificateStoreTests
{
    [Fact]
    public void KeyFingerprint_Is43Characters_AndBase64Url()
    {
        using var store = CertificateStore.InMemory().Certificate;

        var pin = CertificateStore.ComputePin(store);

        Assert.Equal(43, pin.Length);
        Assert.DoesNotContain('=', pin);
        Assert.DoesNotContain('+', pin);
        Assert.DoesNotContain('/', pin);
    }

    [Fact]
    public void TwoCertificates_HaveDifferentFingerprints()
    {
        using var first = CertificateStore.InMemory().Certificate;
        using var second = CertificateStore.InMemory().Certificate;

        Assert.NotEqual(CertificateStore.ComputePin(first), CertificateStore.ComputePin(second));
    }

    [Fact]
    public void ReloadingIt_KeepsTheSameFingerprint()
    {
        // The fingerprint is the server's identity - were it to change on restart, every
        // start-up would invalidate every paired phone.
        using var directory = new TempRoot();

        var first = CertificateStore.LoadOrCreate(directory.Path);
        var second = CertificateStore.LoadOrCreate(directory.Path);

        Assert.Equal(first.Pin, second.Pin);
    }

    [Fact]
    public void CertificateHasPrivateKey_AsKestrelRequires()
    {
        using var directory = new TempRoot();

        var store = CertificateStore.LoadOrCreate(directory.Path);

        Assert.True(store.Certificate.HasPrivateKey);
    }
}

/// <summary>The registry of paired devices.</summary>
public class DeviceRegistryTests
{
    [Fact]
    public void Registration_CreatesTheDirectoryAndTheDeviceRecord()
    {
        using var h = new Harness();
        var registry = new DeviceRegistry(h.Paths);

        var device = registry.Register("PIN-AAA", "Galaxy S24 Ultra");

        Assert.Equal("galaxy-s24-ultra-pinaaa", device.Slug);
        Assert.True(File.Exists(Path.Combine(h.Paths.MetaRoot(device.Slug), "device.json")));
    }

    [Fact]
    public void PairingAgainWithTheSameKey_CreatesNoSecondDirectory()
    {
        // Otherwise the user would lose access to the existing copy on every
        // new scan of the QR code.
        using var h = new Harness();
        var registry = new DeviceRegistry(h.Paths);

        var first = registry.Register("PIN-AAA", "Phone");
        var second = registry.Register("PIN-AAA", "Phone after a rename");

        Assert.Equal(first.Slug, second.Slug);
        Assert.Equal("Phone after a rename", second.Label);
        Assert.Single(registry.All());
    }

    [Fact]
    public void StateSurvivesARestart_BecauseTheDiskIsTheSourceOfTruth()
    {
        using var h = new Harness();
        new DeviceRegistry(h.Paths).Register("PIN-AAA", "Phone");

        var reloaded = new DeviceRegistry(h.Paths);

        Assert.NotNull(reloaded.FindByPin("PIN-AAA"));
    }

    [Fact]
    public void DisablingADevice_Persists()
    {
        using var h = new Harness();
        var registry = new DeviceRegistry(h.Paths);
        registry.Register("PIN-AAA", "Phone");

        registry.SetEnabled("PIN-AAA", false);

        Assert.False(new DeviceRegistry(h.Paths).FindByPin("PIN-AAA")!.Enabled);
    }

    [Theory]
    [InlineData("Galaxy S24 Ultra", "galaxy-s24-ultra-abcdef")]
    [InlineData("  ", "device-abcdef")]
    [InlineData("Phone@#$%Żony", "phone-ony-abcdef")]
    public void DirectoryName_IsLegibleAndSafeForNtfs(string label, string expected)
    {
        Assert.Equal(expected, DeviceRegistry.MakeSlug(label, "abcdef1234"));
    }
}

/// <summary>Pairing tokens (§3.3).</summary>
public class PairingServiceTests
{
    private static PairingService Service(FakeTimeProvider time) => new(time);

    [Fact]
    public void AToken_WorksExactlyOnce()
    {
        var time = new FakeTimeProvider();
        var service = Service(time);
        var token = service.Issue();

        Assert.True(service.TryConsume(token));
        Assert.False(service.TryConsume(token));
    }

    [Fact]
    public void AToken_ExpiresAfter10Minutes()
    {
        var time = new FakeTimeProvider();
        var service = Service(time);
        var token = service.Issue();

        time.Advance(PairingService.Lifetime + TimeSpan.FromSeconds(1));

        Assert.False(service.TryConsume(token));
    }

    [Fact]
    public void AnUnknownToken_IsRejected()
    {
        Assert.False(Service(new FakeTimeProvider()).TryConsume("anything"));
    }

    [Fact]
    public void QrPayload_ContainsTheServerKeyFingerprint()
    {
        var payload = new QrPayload("PC", "SERVER-PIN", "TOKEN", 8765, ["192.168.1.5:8765"], "filum-abc");

        var encoded = PairingService.Encode(payload);

        Assert.StartsWith("filum1:", encoded);
        var json = Encoding.UTF8.GetString(FromBase64Url(encoded["filum1:".Length..]));
        var decoded = JsonSerializer.Deserialize<QrPayload>(json);
        Assert.Equal("SERVER-PIN", decoded!.Key);
        Assert.Equal("TOKEN", decoded.Token);
    }

    private static byte[] FromBase64Url(string value)
    {
        var normalized = value.Replace('-', '+').Replace('_', '/');
        normalized += (normalized.Length % 4) switch { 2 => "==", 3 => "=", _ => "" };
        return Convert.FromBase64String(normalized);
    }
}

public class PairingRateLimiterTests
{
    [Fact]
    public void After5AttemptsFromTheSameAddress_TheNextIsRefused()
    {
        var limiter = new PairingRateLimiter(new FakeTimeProvider());

        for (var i = 0; i < 5; i++) Assert.True(limiter.TryRegisterAttempt("10.0.0.1"));

        Assert.False(limiter.TryRegisterAttempt("10.0.0.1"));
    }

    [Fact]
    public void ADifferentAddress_HasItsOwnLimit()
    {
        var limiter = new PairingRateLimiter(new FakeTimeProvider());
        for (var i = 0; i < 5; i++) limiter.TryRegisterAttempt("10.0.0.1");

        Assert.True(limiter.TryRegisterAttempt("10.0.0.2"));
    }

    [Fact]
    public void AfterTheTimeWindow_TheLimitResets()
    {
        var time = new FakeTimeProvider();
        var limiter = new PairingRateLimiter(time);
        for (var i = 0; i < 5; i++) limiter.TryRegisterAttempt("10.0.0.1");

        time.Advance(TimeSpan.FromMinutes(16));

        Assert.True(limiter.TryRegisterAttempt("10.0.0.1"));
    }
}

/// <summary>
/// Authentication over HTTP: the full pairing exchange and the refusal of everyone else.
/// </summary>
public class AuthenticationTests
{
    [Fact]
    public async Task PairingRegistersTheDevice_AndUnlocksTheResources()
    {
        using var app = new FilumApp();
        var client = app.CreateClient();

        var before = await client.GetAsync("/api/v1/ping");
        Assert.Equal(HttpStatusCode.Unauthorized, before.StatusCode);

        await app.PairAsync();

        var after = await client.GetAsync("/api/v1/ping");
        Assert.Equal(HttpStatusCode.OK, after.StatusCode);
    }

    [Fact]
    public async Task QrCode_CarriesTheServerKeyFingerprint()
    {
        using var app = new FilumApp();

        var offer = await app.NewPairingAsync();

        Assert.NotNull(offer);
        Assert.StartsWith("filum1:", offer.Qr);
        Assert.Equal(43, offer.Payload.Key.Length);
        Assert.Equal(600, offer.ExpiresInSeconds);
    }

    [Fact]
    public async Task Pairing_WithoutAToken_Returns401()
    {
        using var app = new FilumApp();

        var response = await app.PostPairAsync(app.CreateClient(), "", "Phone");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Pairing_WithASpentToken_Returns401()
    {
        using var app = new FilumApp();
        var client = app.CreateClient();
        var offer = await app.NewPairingAsync();

        var first = await app.PostPairAsync(client, offer!.Payload.Token, "Phone");
        var second = await app.PostPairAsync(client, offer.Payload.Token, "Phone");

        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, second.StatusCode);
    }

    [Fact]
    public async Task Pairing_WithABrokenCertificate_Returns400()
    {
        using var app = new FilumApp();
        var offer = await app.NewPairingAsync();

        var response = await app.PostPairAsync(
            app.CreateClient(), offer!.Payload.Token, "Phone", "this is not a certificate");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task AnUnknownClientKey_Returns401()
    {
        using var app = new FilumApp();
        await app.PairAsync();

        using var foreign = CertificateStore.InMemory().Certificate;
        app.Certificates.Current = foreign;

        var response = await app.CreateClient().GetAsync("/api/v1/ping");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task ADisabledDevice_Returns403()
    {
        using var app = new FilumApp();
        await app.PairAsync();

        var registry = app.Services.GetRequiredService<DeviceRegistry>();
        registry.SetEnabled(registry.All().Single().Pin, false);

        var response = await app.CreateClient().GetAsync("/api/v1/ping");
        var error = await response.Content.ReadFromJsonAsync<ErrorResponse>();

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Equal("device_disabled", error!.Error);
    }

    [Fact]
    public async Task WritingAFileWithoutAuthentication_IsImpossible()
    {
        // The most important test at this stage: without a paired key nothing can be
        // written to the disk, which is what makes exposing the server on the LAN safe.
        using var app = new FilumApp();

        var request = new HttpRequestMessage(HttpMethod.Put, "/api/v1/file")
        {
            Content = new ByteArrayContent([1, 2, 3])
        };
        request.Headers.TryAddWithoutValidation(FilumHeaders.Path, "cGxpay50eHQ");
        request.Headers.TryAddWithoutValidation(FilumHeaders.Size, "3");
        request.Headers.TryAddWithoutValidation(FilumHeaders.Mtime, "1756400000");
        request.Headers.TryAddWithoutValidation(FilumHeaders.Offset, "0");

        var response = await app.CreateClient().SendAsync(request);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Empty(Directory.GetDirectories(app.Root));
    }
}

/// <summary>
/// The access rule for the panel and the administrative resources.
///
/// The panel issues pairing tokens and shows what is in the backup, so a stranger's device
/// on the same network must not be able to touch it. The person at the computer is the
/// only entitled audience - and that is the only criterion available here.
/// </summary>
public class PanelAccessTests
{
    private static readonly IPAddress Outsider = IPAddress.Parse("192.168.1.77");

    [Fact]
    public async Task Panel_FromLoopback_IsReachable()
    {
        using var app = new FilumApp();

        var response = await app.CreateClient().GetAsync("/");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Panel_FromTheNetwork_IsRefused()
    {
        using var app = new FilumApp { RemoteAddress = Outsider };

        var response = await app.CreateClient().GetAsync("/");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task TokenIssuing_FromTheNetwork_IsRefused()
    {
        // Otherwise any device on the network could mint itself a token and pair without
        // the computer's owner knowing.
        using var app = new FilumApp { RemoteAddress = Outsider };

        var response = await app.CreateClient().GetAsync("/api/v1/pair/new");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Status_FromTheNetwork_IsRefused()
    {
        using var app = new FilumApp { RemoteAddress = Outsider };

        var response = await app.CreateClient().GetAsync("/api/v1/admin/status");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Status_FromLoopback_ShowsThePairedDevices()
    {
        using var app = new FilumApp();
        await app.PairAsync("Galaxy S24 Ultra");

        var snapshot = await app.CreateClient().GetFromJsonAsync<AdminStatus>("/api/v1/admin/status");

        Assert.NotNull(snapshot);
        Assert.Equal(43, snapshot.Pin.Length);
        Assert.Equal("Galaxy S24 Ultra", snapshot.Devices.Single().Label);
        Assert.True(snapshot.TotalBytes > 0);
    }

    [Fact]
    public async Task Pairing_FromTheNetwork_IsAllowed()
    {
        // The phone pairs over the network, so /pair has to be reachable from outside.
        // What protects it is the single-use token, not the source address.
        using var app = new FilumApp();
        var offer = await app.NewPairingAsync();

        app.RemoteAddress = Outsider;
        var response = await app.PostPairAsync(app.CreateClient(), offer!.Payload.Token, "Phone");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Unpairing_DeletesNoFiles()
    {
        // The most common reason to unpair is a lost phone - which is exactly the moment
        // the backup matters most (invariant I1).
        using var app = new FilumApp();
        var slug = await app.PairAsync();

        var files = Path.Combine(app.Root, slug, "files", "DCIM");
        Directory.CreateDirectory(files);
        await File.WriteAllTextAsync(Path.Combine(files, "a.jpg"), "content");

        var admin = app.Services.GetRequiredService<AdminService>();
        var registry = app.Services.GetRequiredService<DeviceRegistry>();
        Assert.True(admin.Forget(registry.All().Single().Pin));

        Assert.Empty(registry.All());
        Assert.True(File.Exists(Path.Combine(files, "a.jpg")));
    }
}

/// <summary>The pairing code drawn by the panel.</summary>
public class PairingCodeRenderingTests
{
    [Fact]
    public async Task PairingCode_CarriesTheKeyFingerprintAndRendersAsSvg()
    {
        using var app = new FilumApp();
        var admin = app.Services.GetRequiredService<AdminService>();
        var certificates = app.Services.GetRequiredService<CertificateStore>();

        var code = admin.IssuePairingCode();

        Assert.StartsWith("filum1:", code.Payload);
        Assert.Contains(certificates.Pin, Decode(code.Payload));
        Assert.StartsWith("<svg", code.QrSvg);
        Assert.Contains("</svg>", code.QrSvg);
        Assert.Equal(600, code.ExpiresInSeconds);

        await Task.CompletedTask;
    }

    [Fact]
    public async Task EveryCall_YieldsADifferentToken()
    {
        // The token is single-use, so two codes in a row cannot be the same code -
        // otherwise a second phone would have nothing to pair with.
        using var app = new FilumApp();
        var admin = app.Services.GetRequiredService<AdminService>();

        Assert.NotEqual(admin.IssuePairingCode().Payload, admin.IssuePairingCode().Payload);

        await Task.CompletedTask;
    }

    private static string Decode(string payload)
    {
        var value = payload["filum1:".Length..].Replace('-', '+').Replace('_', '/');
        value += (value.Length % 4) switch { 2 => "==", 3 => "=", _ => "" };
        return Encoding.UTF8.GetString(Convert.FromBase64String(value));
    }
}

/// <summary>Advertising the service on the local network (protocol §4.1).</summary>
public class MdnsAdvertiserTests
{
    [Fact]
    public void InstanceName_MatchesTheFieldInThePairingCode()
    {
        // The phone matches an advertisement to the server it paired with by exactly this
        // string - a divergence between the two places would mean discovery finds the
        // server while the client fails to recognise it.
        const string pin = "EhZa6VgNQovoNrFs_e2PcVbgKr8EQf4ZzTQI5slNr7A";

        Assert.Equal("filum-EhZa6VgN", MdnsAdvertiser.BuildInstanceName(pin));
        Assert.Equal($"filum-{pin[..8]}", MdnsAdvertiser.BuildInstanceName(pin));
    }

    [Fact]
    public void TheServiceType_MatchesTheSpecification()
    {
        Assert.Equal("_filum._tcp", MdnsAdvertiser.ServiceType);
    }

    [Fact]
    public async Task FailedAdvertising_DoesNotStopTheServerStarting()
    {
        // Advertising is a convenience, not a precondition: a firewall or a network
        // without multicast must not stop the server from accepting a backup.
        using var app = new FilumApp();

        var response = await app.CreateClient().GetAsync("/api/v1/pair/new");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }
}
