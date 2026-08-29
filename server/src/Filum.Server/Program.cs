using Filum.Core;
using Filum.Server;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

builder.Services.Configure<FilumOptions>(builder.Configuration.GetSection(FilumOptions.SectionName));
builder.Services.AddSingleton(sp => sp.GetRequiredService<IOptions<FilumOptions>>().Value);

builder.Services.AddSingleton(sp =>
{
    var options = sp.GetRequiredService<FilumOptions>();
    var root = string.IsNullOrWhiteSpace(options.Root)
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Filum", "backup")
        : options.Root;

    Directory.CreateDirectory(LongPath.Extended(root));
    return new StoragePaths(root);
});

builder.Services.AddSingleton<SpacePolicy>();
builder.Services.AddSingleton<VersionStore>();
builder.Services.AddSingleton<ManifestResolver>();
builder.Services.AddSingleton<TransferStore>();
builder.Services.AddSingleton<DeviceStats>();
builder.Services.AddSingleton<IDeviceResolver, ConfiguredDeviceResolver>();

// Transfery bywają wielogigabajtowe, a domyślny limit ciała żądania w Kestrelu to 30 MB.
// Ochroną przed nadmiarowymi danymi jest X-Filum-Size i ograniczone kopiowanie
// w TransferStore, nie limit transportowy.
builder.WebHost.ConfigureKestrel(kestrel => kestrel.Limits.MaxRequestBodySize = null);

var app = builder.Build();

app.MapFilumApi();

var paths = app.Services.GetRequiredService<StoragePaths>();
app.Logger.LogInformation("Filum: katalog backupu {Root}", paths.Root);
app.Logger.LogWarning(
    "Etap 2: brak TLS i uwierzytelniania, nasłuch tylko na 127.0.0.1. " +
    "Nie wolno wystawiać tego serwera na adres osiągalny spoza komputera przed etapem 4.");

app.Run();

/// <summary>Widoczne dla WebApplicationFactory w testach integracyjnych.</summary>
public partial class Program;
