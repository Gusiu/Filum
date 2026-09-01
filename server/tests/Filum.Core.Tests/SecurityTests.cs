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

/// <summary>Certyfikat serwera i odciski kluczy (§3.1 protokołu, ADR-005).</summary>
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
        // Odcisk jest tożsamością serwera — gdyby zmieniał się przy restarcie,
        // każde uruchomienie unieważniałoby wszystkie sparowane telefony.
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

/// <summary>Rejestr sparowanych urządzeń.</summary>
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
        // Inaczej użytkownik gubiłby dostęp do dotychczasowej kopii przy każdym
        // ponownym zeskanowaniu kodu QR.
        using var h = new Harness();
        var registry = new DeviceRegistry(h.Paths);

        var first = registry.Register("PIN-AAA", "Telefon");
        var second = registry.Register("PIN-AAA", "Telefon po zmianie nazwy");

        Assert.Equal(first.Slug, second.Slug);
        Assert.Equal("Telefon po zmianie nazwy", second.Label);
        Assert.Single(registry.All());
    }

    [Fact]
    public void StateSurvivesARestart_BecauseTheDiskIsTheSourceOfTruth()
    {
        using var h = new Harness();
        new DeviceRegistry(h.Paths).Register("PIN-AAA", "Telefon");

        var reloaded = new DeviceRegistry(h.Paths);

        Assert.NotNull(reloaded.FindByPin("PIN-AAA"));
    }

    [Fact]
    public void DisablingADevice_Persists()
    {
        using var h = new Harness();
        var registry = new DeviceRegistry(h.Paths);
        registry.Register("PIN-AAA", "Telefon");

        registry.SetEnabled("PIN-AAA", false);

        Assert.False(new DeviceRegistry(h.Paths).FindByPin("PIN-AAA")!.Enabled);
    }

    [Theory]
    [InlineData("Galaxy S24 Ultra", "galaxy-s24-ultra-abcdef")]
    [InlineData("  ", "urzadzenie-abcdef")]
    [InlineData("Telefon@#$%Żony", "telefon-ony-abcdef")]
    public void DirectoryName_IsLegibleAndSafeForNtfs(string label, string expected)
    {
        Assert.Equal(expected, DeviceRegistry.MakeSlug(label, "abcdef1234"));
    }
}

/// <summary>Tokeny parowania (§3.3).</summary>
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
        Assert.False(Service(new FakeTimeProvider()).TryConsume("cokolwiek"));
    }

    [Fact]
    public void QrPayload_ContainsTheServerKeyFingerprint()
    {
        var payload = new QrPayload("PC", "PIN-SERWERA", "TOKEN", 8765, ["192.168.1.5:8765"], "filum-abc");

        var encoded = PairingService.Encode(payload);

        Assert.StartsWith("filum1:", encoded);
        var json = Encoding.UTF8.GetString(FromBase64Url(encoded["filum1:".Length..]));
        var decoded = JsonSerializer.Deserialize<QrPayload>(json);
        Assert.Equal("PIN-SERWERA", decoded!.Key);
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
/// Uwierzytelnianie przez HTTP: pełny przebieg parowania i odrzucanie nieuprawnionych.
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

        var response = await app.PostPairAsync(app.CreateClient(), "", "Telefon");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Pairing_WithASpentToken_Returns401()
    {
        using var app = new FilumApp();
        var client = app.CreateClient();
        var offer = await app.NewPairingAsync();

        var first = await app.PostPairAsync(client, offer!.Payload.Token, "Telefon");
        var second = await app.PostPairAsync(client, offer.Payload.Token, "Telefon");

        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, second.StatusCode);
    }

    [Fact]
    public async Task Pairing_WithABrokenCertificate_Returns400()
    {
        using var app = new FilumApp();
        var offer = await app.NewPairingAsync();

        var response = await app.PostPairAsync(
            app.CreateClient(), offer!.Payload.Token, "Telefon", "to nie jest certyfikat");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task AnUnknownClientKey_Returns401()
    {
        using var app = new FilumApp();
        await app.PairAsync();

        using var obcy = CertificateStore.InMemory().Certificate;
        app.Certificates.Current = obcy;

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
        // Najważniejszy test tego etapu: bez sparowanego klucza nie da się nic zapisać
        // na dysku, więc serwer wolno wystawić na sieć lokalną.
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
/// Reguła dostępu do panelu i zasobów administracyjnych.
///
/// Panel wydaje tokeny parowania i pokazuje, co leży w kopii, więc obce urządzenie
/// w tej samej sieci nie może go dotknąć. Osoba przy komputerze jest jedynym
/// uprawnionym odbiorcą — i to jest jedyne kryterium, jakie tu mamy.
/// </summary>
public class PanelAccessTests
{
    private static readonly IPAddress Obcy = IPAddress.Parse("192.168.1.77");

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
        using var app = new FilumApp { RemoteAddress = Obcy };

        var response = await app.CreateClient().GetAsync("/");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task TokenIssuing_FromTheNetwork_IsRefused()
    {
        // Inaczej dowolne urządzenie w sieci mogłoby wygenerować sobie token
        // i sparować się bez wiedzy właściciela komputera.
        using var app = new FilumApp { RemoteAddress = Obcy };

        var response = await app.CreateClient().GetAsync("/api/v1/pair/new");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Status_FromTheNetwork_IsRefused()
    {
        using var app = new FilumApp { RemoteAddress = Obcy };

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
        // Telefon paruje się przez sieć, więc /pair musi być osiągalne z zewnątrz.
        // Chroni go token jednorazowy, a nie adres źródłowy.
        using var app = new FilumApp();
        var offer = await app.NewPairingAsync();

        app.RemoteAddress = Obcy;
        var response = await app.PostPairAsync(app.CreateClient(), offer!.Payload.Token, "Telefon");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Unpairing_DeletesNoFiles()
    {
        // Najczęstszy powód rozparowania to zgubiony telefon — czyli dokładnie ta
        // chwila, w której kopia jest najbardziej potrzebna (niezmiennik I1).
        using var app = new FilumApp();
        var slug = await app.PairAsync();

        var files = Path.Combine(app.Root, slug, "files", "DCIM");
        Directory.CreateDirectory(files);
        await File.WriteAllTextAsync(Path.Combine(files, "a.jpg"), "zawartość");

        var admin = app.Services.GetRequiredService<AdminService>();
        var registry = app.Services.GetRequiredService<DeviceRegistry>();
        Assert.True(admin.Forget(registry.All().Single().Pin));

        Assert.Empty(registry.All());
        Assert.True(File.Exists(Path.Combine(files, "a.jpg")));
    }
}

/// <summary>Kod parowania rysowany przez panel.</summary>
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
        // Token jest jednorazowy, więc dwa kody z rzędu nie mogą być tym samym kodem —
        // inaczej drugi telefon nie miałby czym się sparować.
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

/// <summary>Rozgłaszanie usługi w sieci lokalnej (§4.1 protokołu).</summary>
public class MdnsAdvertiserTests
{
    [Fact]
    public void InstanceName_MatchesTheFieldInThePairingCode()
    {
        // Telefon dopasowuje rozgłoszenie do serwera, z którym się sparował, po tym
        // właśnie ciągu — rozjazd między tymi dwoma miejscami sprawiłby, że
        // wykrywanie znajduje serwer, ale klient go nie rozpoznaje.
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
        // Rozgłaszanie jest wygodą, nie warunkiem działania: zapora albo sieć bez
        // obsługi multicastu nie mogą uniemożliwić przyjmowania kopii zapasowej.
        using var app = new FilumApp();

        var response = await app.CreateClient().GetAsync("/api/v1/pair/new");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }
}
