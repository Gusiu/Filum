using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Filum.Core;

namespace Filum.Server;

/// <summary>
/// Per-device file and byte counters, reported in the ping response
/// i w panelu.
///
/// Ping takes part in the route race (§4.2), so it has to be cheap. Walking the tree on
/// every call would be unacceptable across a hundred thousand files, so the value comes
/// from a cache and the refresh happens in the background
/// i nigdy nie blokuje odpowiedzi.
/// </summary>
public sealed class DeviceStats(StoragePaths paths, ILogger<DeviceStats> logger)
{
    private sealed record Entry(long Count, long Bytes, DateTime ComputedUtc);

    private static readonly TimeSpan Ttl = TimeSpan.FromSeconds(60);

    private readonly ConcurrentDictionary<string, Entry> _cache = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> _running = new(StringComparer.Ordinal);

    /// <summary>The last computed values; refreshed in the background once stale.</summary>
    public (long Count, long Bytes) For(string slug)
    {
        var entry = _cache.GetValueOrDefault(slug);
        if (entry is null || DateTime.UtcNow - entry.ComputedUtc >= Ttl) Refresh(slug);

        return entry is null ? (0, 0) : (entry.Count, entry.Bytes);
    }

    private void Refresh(string slug)
    {
        if (!_running.TryAdd(slug, 0)) return;

        _ = Task.Run(() =>
        {
            try
            {
                var (count, bytes) = Compute(slug);
                _cache[slug] = new Entry(count, bytes, DateTime.UtcNow);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                logger.LogWarning(ex, "Could not recompute statistics for device {Slug}", slug);
            }
            finally
            {
                _running.TryRemove(slug, out _);
            }
        });
    }

    private (long Count, long Bytes) Compute(string slug)
    {
        var root = LongPath.Extended(paths.FilesRoot(slug));
        if (!Directory.Exists(root)) return (0, 0);

        long count = 0;
        long bytes = 0;
        foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            count++;
            bytes += new FileInfo(file).Length;
        }

        return (count, bytes);
    }
}

/// <summary>
/// The addresses the server sees itself under. They go into the ping response, which is
/// what lets the client's route set update itself (§4.4) — one successful connection by
/// any road refreshes all the others.
/// </summary>
public static class LocalEndpoints
{
    public static IReadOnlyList<string> Enumerate(int port)
    {
        var addresses = new List<string>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                if (IPAddress.IsLoopback(unicast.Address)) continue;
                addresses.Add($"{unicast.Address}:{port}");
            }
        }

        return addresses;
    }
}
