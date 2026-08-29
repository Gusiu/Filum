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
    public void OdciskKlucza_Ma43Znaki_IJestBase64Url()
    {
        using var store = CertificateStore.InMemory().Certificate;

        var pin = CertificateStore.ComputePin(store);

        Assert.Equal(43, pin.Length);
        Assert.DoesNotContain('=', pin);
        Assert.DoesNotContain('+', pin);
        Assert.DoesNotContain('/', pin);
    }

    [Fact]
    public void DwaCertyfikaty_MajaRozneOdciski()
    {
        using var first = CertificateStore.InMemory().Certificate;
        using var second = CertificateStore.InMemory().Certificate;

        Assert.NotEqual(CertificateStore.ComputePin(first), CertificateStore.ComputePin(second));
    }

    [Fact]
    public void PonowneWczytanie_ZachowujeTenSamOdcisk()
    {
        // Odcisk jest tożsamością serwera — gdyby zmieniał się przy restarcie,
        // każde uruchomienie unieważniałoby wszystkie sparowane telefony.
        using var directory = new TempRoot();

        var first = CertificateStore.LoadOrCreate(directory.Path);
        var second = CertificateStore.LoadOrCreate(directory.Path);

        Assert.Equal(first.Pin, second.Pin);
    }

    [Fact]
    public void CertyfikatMaKluczPrywatny_WymaganyPrzezKestrela()
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
    public void Rejestracja_TworzyKatalogIZapisDevice()
    {
        using var h = new Harness();
        var registry = new DeviceRegistry(h.Paths);

        var device = registry.Register("PIN-AAA", "Galaxy S24 Ultra");

        Assert.Equal("galaxy-s24-ultra-pinaaa", device.Slug);
        Assert.True(File.Exists(Path.Combine(h.Paths.MetaRoot(device.Slug), "device.json")));
    }

    [Fact]
    public void PonowneParowanieTymSamymKluczem_NieTworzyDrugiegoKatalogu()
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
    public void StanPrzezywaRestart_BoZrodlemPrawdyJestDysk()
    {
        using var h = new Harness();
        new DeviceRegistry(h.Paths).Register("PIN-AAA", "Telefon");

        var reloaded = new DeviceRegistry(h.Paths);

        Assert.NotNull(reloaded.FindByPin("PIN-AAA"));
    }

    [Fact]
    public void WylaczenieUrzadzenia_JestTrwale()
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
    public void NazwaKatalogu_JestCzytelnaIBezpiecznaDlaNtfs(string label, string expected)
    {
        Assert.Equal(expected, DeviceRegistry.MakeSlug(label, "abcdef1234"));
    }
}

/// <summary>Tokeny parowania (§3.3).</summary>
public class PairingServiceTests
{
    private static PairingService Service(FakeTimeProvider time) => new(time);

    [Fact]
    public void Token_DzialaDokladnieRaz()
    {
        var time = new FakeTimeProvider();
        var service = Service(time);
        var token = service.Issue();

        Assert.True(service.TryConsume(token));
        Assert.False(service.TryConsume(token));
    }

    [Fact]
    public void Token_WygasaPo10Minutach()
    {
        var time = new FakeTimeProvider();
        var service = Service(time);
        var token = service.Issue();

        time.Advance(PairingService.Lifetime + TimeSpan.FromSeconds(1));

        Assert.False(service.TryConsume(token));
    }

    [Fact]
    public void NieznanyToken_JestOdrzucany()
    {
        Assert.False(Service(new FakeTimeProvider()).TryConsume("cokolwiek"));
    }

    [Fact]
    public void LadunekKoduQr_ZawieraOdciskKluczaSerwera()
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
    public void Po5ProbachZTegoSamegoAdresu_KolejnaJestOdrzucana()
    {
        var limiter = new PairingRateLimiter(new FakeTimeProvider());

        for (var i = 0; i < 5; i++) Assert.True(limiter.TryRegisterAttempt("10.0.0.1"));

        Assert.False(limiter.TryRegisterAttempt("10.0.0.1"));
    }

    [Fact]
    public void InnyAdres_MaWlasnyLimit()
    {
        var limiter = new PairingRateLimiter(new FakeTimeProvider());
        for (var i = 0; i < 5; i++) limiter.TryRegisterAttempt("10.0.0.1");

        Assert.True(limiter.TryRegisterAttempt("10.0.0.2"));
    }

    [Fact]
    public void PoOknieCzasowym_LimitSieOdnawia()
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
    public async Task ParowanieRejestrujeUrzadzenie_IOdblokowujeZasoby()
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
    public async Task KodQr_NiesieOdciskKluczaSerwera()
    {
        using var app = new FilumApp();

        var offer = await app.NewPairingAsync();

        Assert.NotNull(offer);
        Assert.StartsWith("filum1:", offer.Qr);
        Assert.Equal(43, offer.Payload.Key.Length);
        Assert.Equal(600, offer.ExpiresInSeconds);
    }

    [Fact]
    public async Task Parowanie_BezTokenu_Zwraca401()
    {
        using var app = new FilumApp();

        var response = await app.PostPairAsync(app.CreateClient(), "", "Telefon");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Parowanie_ZuzytymTokenem_Zwraca401()
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
    public async Task Parowanie_ZepsutymCertyfikatem_Zwraca400()
    {
        using var app = new FilumApp();
        var offer = await app.NewPairingAsync();

        var response = await app.PostPairAsync(
            app.CreateClient(), offer!.Payload.Token, "Telefon", "to nie jest certyfikat");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task NieznanyKluczKlienta_Zwraca401()
    {
        using var app = new FilumApp();
        await app.PairAsync();

        using var obcy = CertificateStore.InMemory().Certificate;
        app.Certificates.Current = obcy;

        var response = await app.CreateClient().GetAsync("/api/v1/ping");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task WylaczoneUrzadzenie_Zwraca403()
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
    public async Task ZapisPlikuBezUwierzytelnienia_JestNiemozliwy()
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
