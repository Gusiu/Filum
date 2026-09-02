using System.Reflection;
using Filum.Core;
using Filum.Protocol;
using QRCoder;

namespace Filum.Server;

public sealed record PairingCode(string Payload, string QrSvg, int ExpiresInSeconds);

/// <summary>
/// Dane i operacje panelu.
///
/// Everything here requires a loopback connection — the panel issues pairing tokens and
/// shows the contents of the backup, so the person at the computer is the only entitled
/// audience (see <see cref="DeviceAuthenticationMiddleware"/>).
/// </summary>
public sealed class AdminService(
    FilumOptions options,
    StoragePaths paths,
    SpacePolicy space,
    DeviceRegistry registry,
    CertificateStore certificates,
    DeviceStats stats,
    OrphanReporter orphans,
    IntegrityLedger ledger,
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
                    report?.Count ?? 0, report?.Bytes ?? 0,
                    LedgerOf(device.Slug));
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

    /// <summary>The device's latest orphan report; null when no full reconciliation has run yet.</summary>
    public OrphanReport? Orphans(string slug) => orphans.Latest(slug);

    /// <summary>
    /// Revokes a device's access but **does not delete its files**.
    ///
    /// Invariant I1 holds here too: unpairing a phone must not erase the copy, because the
    /// most common reason to unpair is a lost or replaced phone — exactly the moment the
    /// backup matters most.
    /// </summary>
    public bool Forget(string pin) => registry.Forget(pin);

    /// <summary>
    /// The device's ledger state.
    ///
    /// Verification walks the entire ledger, so across a hundred thousand entries it costs
    /// reading a dozen or so megabytes of text. That is one sequential read per visit to
    /// the panel, not per request from a phone — and without it the panel would show an
    /// entry count with no indication of whether any of it is still trustworthy.
    /// </summary>
    private AdminLedger? LedgerOf(string slug)
    {
        var result = ledger.Verify(slug);
        if (result.Entries == 0 && result.Intact) return null;

        return new AdminLedger(result.Entries, result.Intact, result.BrokenAtSequence, result.Problem);
    }

    /// <summary>When something last arrived in the device's directory.</summary>
    private DateTimeOffset? LastActivity(string slug)
    {
        var directory = LongPath.Extended(paths.FilesRoot(slug));
        if (!Directory.Exists(directory)) return null;

        return new DateTimeOffset(Directory.GetLastWriteTimeUtc(directory), TimeSpan.Zero);
    }

    /// <summary>
    /// The QR code as SVG rather than PNG: it scales without blurring, and edge sharpness
    /// matters when a phone is doing the scanning.
    /// </summary>
    private static string RenderQr(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        return new SvgQRCode(data).GetGraphic(4);
    }
}
