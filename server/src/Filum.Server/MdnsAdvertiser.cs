using Filum.Core;
using Makaretu.Dns;

namespace Filum.Server;

/// <summary>
/// Advertising the service on the local network over mDNS (protocol §4.1).
///
/// Why bother, when the client keeps a route set and refreshes it itself (§4.4)? Because
/// that mechanism has one gap: it updates only when **at least one** stored route works.
/// A phone that was away from home during the week the router changed, and has no overlay
/// network, has nothing to bootstrap from.
///
/// mDNS closes that gap: on the same network the server can be found without knowing its
/// address, and the key fingerprint in the TXT record rejects other people's instances
/// before a connection is even attempted.
/// </summary>
public sealed class MdnsAdvertiser(
    FilumOptions options,
    CertificateStore certificates,
    ILogger<MdnsAdvertiser> logger) : IHostedService, IDisposable
{
    /// <summary>The service type from §4.1. The name is part of the protocol and cannot change.</summary>
    public const string ServiceType = "_filum._tcp";

    private MulticastService? _multicast;
    private ServiceDiscovery? _discovery;

    public string InstanceName => BuildInstanceName(certificates.Pin);

    /// <summary>
    /// The instance name: <c>filum-</c> plus the first eight characters of the key fingerprint.
    ///
    /// The same string goes into the pairing code's <c>s</c> field, so the phone can match
    /// an advertisement to the server it paired with even before
    /// it reads the TXT record.
    /// </summary>
    public static string BuildInstanceName(string pin) => $"filum-{pin[..8]}";

    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (!options.EnableMdns)
        {
            logger.LogInformation("Filum: mDNS advertising disabled in configuration");
            return Task.CompletedTask;
        }

        try
        {
            var profile = new ServiceProfile(InstanceName, ServiceType, (ushort)options.Port);

            profile.AddProperty("v", "1");
            profile.AddProperty("k", certificates.Pin);
            profile.AddProperty("n", Environment.MachineName);

            _multicast = new MulticastService();
            _discovery = new ServiceDiscovery(_multicast);
            _discovery.Advertise(profile);
            _multicast.Start();

            logger.LogInformation("Filum: advertising {Instance}.{Type} on port {Port}",
                                  InstanceName, ServiceType, options.Port);
        }
        catch (Exception ex)
        {
            // Advertising is a convenience, not a precondition. A firewall, missing
            // multicast permissions or a network without multicast support must not
            // stop the server from accepting a backup.
            logger.LogWarning(ex, "Filum: could not start mDNS advertising; " +
                                  "the phone will still find the server by its stored addresses");
            Dispose();
        }

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        Dispose();
        return Task.CompletedTask;
    }

    public void Dispose()
    {
        _discovery?.Dispose();
        _discovery = null;

        _multicast?.Stop();
        _multicast?.Dispose();
        _multicast = null;
    }
}
