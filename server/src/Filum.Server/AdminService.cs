using System.Reflection;
using Filum.Core;
using Filum.Protocol;
using QRCoder;

namespace Filum.Server;

public sealed record PairingCode(string Payload, string QrSvg, int ExpiresInSeconds);

/// <summary>
/// Dane i operacje panelu.
///
/// Wszystko, co tu jest, wymaga połączenia z pętli zwrotnej — panel wydaje tokeny
/// parowania i pokazuje zawartość kopii, więc osoba przy komputerze jest jedynym
/// uprawnionym odbiorcą (patrz <see cref="DeviceAuthenticationMiddleware"/>).
/// </summary>
public sealed class AdminService(
    FilumOptions options,
    StoragePaths paths,
    SpacePolicy space,
    DeviceRegistry registry,
    CertificateStore certificates,
    DeviceStats stats,
    OrphanReporter orphans,
    PairingService pairing)
{
    public AdminStatus Snapshot()
    {
        var devices = registry.All()
            .Select(device =>
            {
                var (count, bytes) = stats.For(device.Slug);
                var report = orphans.Latest(device.Slug);

                return new AdminDevice(
                    device.Pin, device.Slug, device.Label, device.Enabled, device.PairedAt,
                    count, bytes, LastActivity(device.Slug),
                    report?.Count ?? 0, report?.Bytes ?? 0);
            })
            .ToList();

        return new AdminStatus(
            ServerName: Environment.MachineName,
            Version: Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0",
            Pin: certificates.Pin,
            Endpoints: LocalEndpoints.Enumerate(options.Port),
            BackupRoot: paths.Root,
            FreeBytes: space.FreeBytes(paths.Root),
            TotalBytes: space.TotalBytes(paths.Root),
            Devices: devices);
    }

    /// <summary>Wydaje jednorazowy token i rysuje kod QR do zeskanowania telefonem.</summary>
    public PairingCode IssuePairingCode()
    {
        var payload = PairingService.Encode(new QrPayload(
            Environment.MachineName,
            certificates.Pin,
            pairing.Issue(),
            options.Port,
            LocalEndpoints.Enumerate(options.Port),
            $"filum-{certificates.Pin[..8]}"));

        return new PairingCode(payload, RenderQr(payload), (int)PairingService.Lifetime.TotalSeconds);
    }

    public bool SetEnabled(string pin, bool enabled) => registry.SetEnabled(pin, enabled);

    /// <summary>Najnowszy raport sierot urządzenia; null, gdy pełna rekoncyliacja jeszcze nie przeszła.</summary>
    public OrphanReport? Orphans(string slug) => orphans.Latest(slug);

    /// <summary>
    /// Odbiera urządzeniu dostęp, ale **nie usuwa jego plików**.
    ///
    /// Niezmiennik I1 obowiązuje także tutaj: rozparowanie telefonu nie może kasować
    /// kopii, bo najczęstszy powód rozparowania to zgubiony albo wymieniony telefon,
    /// czyli dokładnie ta chwila, w której kopia jest najbardziej potrzebna.
    /// </summary>
    public bool Forget(string pin) => registry.Forget(pin);

    /// <summary>Kiedy do katalogu urządzenia trafiło coś ostatnio.</summary>
    private DateTimeOffset? LastActivity(string slug)
    {
        var directory = LongPath.Extended(paths.FilesRoot(slug));
        if (!Directory.Exists(directory)) return null;

        return new DateTimeOffset(Directory.GetLastWriteTimeUtc(directory), TimeSpan.Zero);
    }

    /// <summary>
    /// Kod QR jako SVG, nie PNG: skaluje się bez rozmycia, a przy skanowaniu telefonem
    /// ostrość krawędzi ma znaczenie.
    /// </summary>
    private static string RenderQr(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        return new SvgQRCode(data).GetGraphic(4);
    }
}
