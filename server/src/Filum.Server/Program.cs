using System.Net;
using Filum.Core;
using Filum.Server;
using Filum.Server.Components;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

builder.Services.Configure<FilumOptions>(builder.Configuration.GetSection(FilumOptions.SectionName));
builder.Services.AddSingleton(sp => sp.GetRequiredService<IOptions<FilumOptions>>().Value);
builder.Services.AddSingleton(TimeProvider.System);

// Konfiguracja jest potrzebna, zanim powstanie kontener — Kestrel musi dostać
// certyfikat już na etapie budowania nasłuchu.
var options = builder.Configuration.GetSection(FilumOptions.SectionName).Get<FilumOptions>() ?? new FilumOptions();
var backupRoot = FilumPaths.ResolveRoot(options);
var configDirectory = FilumPaths.ResolveConfigDirectory(options);
var certificates = CertificateStore.LoadOrCreate(configDirectory);

builder.Services.AddSingleton(certificates);
builder.Services.AddSingleton(new StoragePaths(backupRoot));
builder.Services.AddSingleton<DeviceRegistry>();
builder.Services.AddSingleton<SpacePolicy>();
builder.Services.AddSingleton<VersionStore>();
builder.Services.AddSingleton<ManifestResolver>();
builder.Services.AddSingleton<TransferStore>();
builder.Services.AddSingleton<BundleReader>();
builder.Services.AddSingleton<DeviceStats>();
builder.Services.AddSingleton<PairingService>();
builder.Services.AddSingleton<PairingRateLimiter>();
builder.Services.AddSingleton<IClientCertificateProvider, ConnectionClientCertificateProvider>();
builder.Services.AddSingleton<AdminService>();

// Rozgłaszanie w sieci lokalnej. Wygoda, nie warunek działania: gdy multicast
// nie przejdzie, telefon nadal znajdzie serwer po zapisanych adresach (§4.2).
builder.Services.AddSingleton<MdnsAdvertiser>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<MdnsAdvertiser>());

// Panel serwera. Blazor po stronie serwera, bo stan i tak tu mieszka, a przy
// dostępie wyłącznie z pętli zwrotnej opóźnienie kanału jest nieodczuwalne.
builder.Services.AddRazorComponents().AddInteractiveServerComponents();

builder.WebHost.ConfigureKestrel(kestrel =>
{
    // Transfery bywają wielogigabajtowe, a domyślny limit ciała żądania to 30 MB.
    // Ochroną przed nadmiarem jest X-Filum-Size i ograniczone kopiowanie w TransferStore,
    // nie limit transportowy.
    kestrel.Limits.MaxRequestBodySize = null;

    kestrel.Listen(IPAddress.Parse(options.Bind), options.Port, listen =>
    {
        listen.UseHttps(https =>
        {
            https.ServerCertificate = certificates.Certificate;

            // AllowCertificate, nie RequireCertificate: /api/v1/pair musi być osiągalne
            // zanim urządzenie w ogóle ma czym się przedstawić.
            https.ClientCertificateMode = ClientCertificateMode.AllowCertificate;

            // Łańcuch zaufania nas nie interesuje — certyfikaty są samopodpisane z definicji
            // (ADR-005). Właściwa weryfikacja to porównanie odcisku klucza z listą
            // sparowanych urządzeń, w DeviceAuthenticationMiddleware.
            https.ClientCertificateValidation = (_, _, _) => true;
        });
    });
});

var app = builder.Build();

app.UseMiddleware<DeviceAuthenticationMiddleware>();

app.UseStaticFiles();

// Wymagane przez interaktywne komponenty Blazora. Panel i tak jest dostępny wyłącznie
// z pętli zwrotnej, ale to zabezpieczenie kosztuje jedną linię i chroni przed
// żądaniem wywołanym z otwartej obok strony w przeglądarce.
app.UseAntiforgery();

app.MapFilumApi();
app.MapRazorComponents<App>().AddInteractiveServerRenderMode();

app.Logger.LogInformation("Filum: katalog backupu {Root}", backupRoot);
app.Logger.LogInformation("Filum: certyfikat {Path}", certificates.Path);
app.Logger.LogInformation("Filum: odcisk klucza serwera {Pin}", certificates.Pin);
app.Logger.LogInformation("Filum: nasłuch https://{Bind}:{Port}", options.Bind, options.Port);

var known = app.Services.GetRequiredService<DeviceRegistry>().All();
app.Logger.LogInformation("Filum: sparowanych urządzeń: {Count}", known.Count);
if (known.Count == 0)
{
    app.Logger.LogInformation(
        "Filum: aby sparować telefon, otwórz panel pod https://127.0.0.1:{Port}/parowanie", options.Port);
}

app.Run();

/// <summary>Rozwiązywanie katalogów, wspólne dla startu serwera i testów.</summary>
public static class FilumPaths
{
    public static string ResolveRoot(FilumOptions options)
    {
        var root = string.IsNullOrWhiteSpace(options.Root)
            ? Path.Combine(LocalAppData(), "Filum", "backup")
            : options.Root;

        Directory.CreateDirectory(LongPath.Extended(root));
        return root;
    }

    /// <summary>
    /// Katalog na klucz prywatny serwera. Celowo POZA katalogiem backupu: ten bywa
    /// kopiowany na inny dysk albo odtwarzany z kopii, więc klucz wyciekłby razem z danymi.
    /// </summary>
    public static string ResolveConfigDirectory(FilumOptions options) =>
        string.IsNullOrWhiteSpace(options.ConfigDirectory)
            ? Path.Combine(LocalAppData(), "Filum")
            : options.ConfigDirectory;

    private static string LocalAppData() =>
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
}

/// <summary>Widoczne dla WebApplicationFactory w testach integracyjnych.</summary>
public partial class Program;
