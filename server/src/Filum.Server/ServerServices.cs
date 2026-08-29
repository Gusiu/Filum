using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Filum.Core;

namespace Filum.Server;

/// <summary>
/// Liczniki plików i bajtów per urządzenie, prezentowane w odpowiedzi na ping
/// i w panelu.
///
/// Ping bierze udział w wyścigu tras (§4.2), więc musi być tani. Przechodzenie
/// drzewa przy każdym wywołaniu byłoby nie do przyjęcia przy 100 tys. plików,
/// dlatego wartość pochodzi z pamięci podręcznej, a odświeżenie idzie w tle
/// i nigdy nie blokuje odpowiedzi.
/// </summary>
public sealed class DeviceStats(StoragePaths paths, ILogger<DeviceStats> logger)
{
    private sealed record Entry(long Count, long Bytes, DateTime ComputedUtc);

    private static readonly TimeSpan Ttl = TimeSpan.FromSeconds(60);

    private readonly ConcurrentDictionary<string, Entry> _cache = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> _running = new(StringComparer.Ordinal);

    /// <summary>Ostatnio policzone wartości; odświeża je w tle, gdy się zestarzały.</summary>
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
                logger.LogWarning(ex, "Nie udało się przeliczyć statystyk urządzenia {Slug}", slug);
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
/// Adresy, pod którymi serwer sam siebie widzi. Trafiają do odpowiedzi na ping, dzięki
/// czemu zbiór tras u klienta aktualizuje się sam (§4.4) — jedno udane połączenie
/// dowolną drogą odświeża wszystkie pozostałe.
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
