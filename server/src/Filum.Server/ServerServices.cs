using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Filum.Core;

namespace Filum.Server;

/// <summary>
/// Licznik plików i bajtów urządzenia, prezentowany w odpowiedzi na ping.
///
/// Ping bierze udział w wyścigu tras (§4.2), więc musi być tani. Przechodzenie drzewa
/// przy każdym wywołaniu byłoby nie do przyjęcia przy 100 tys. plików, dlatego wartość
/// jest z pamięci podręcznej, a odświeżenie idzie w tle i nigdy nie blokuje odpowiedzi.
/// </summary>
public sealed class DeviceStats(StoragePaths paths, ILogger<DeviceStats> logger)
{
    private long _count;
    private long _bytes;
    private long _computedTicks;
    private int _running;

    public (long Count, long Bytes) Current => (Interlocked.Read(ref _count), Interlocked.Read(ref _bytes));

    public void RefreshIfStale(string slug, TimeSpan ttl)
    {
        var computed = new DateTime(Interlocked.Read(ref _computedTicks), DateTimeKind.Utc);
        if (DateTime.UtcNow - computed < ttl) return;
        if (Interlocked.Exchange(ref _running, 1) == 1) return;

        _ = Task.Run(() =>
        {
            try
            {
                Compute(slug);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                logger.LogWarning(ex, "Nie udało się przeliczyć statystyk urządzenia {Slug}", slug);
            }
            finally
            {
                Interlocked.Exchange(ref _computedTicks, DateTime.UtcNow.Ticks);
                Interlocked.Exchange(ref _running, 0);
            }
        });
    }

    private void Compute(string slug)
    {
        var root = LongPath.Extended(paths.FilesRoot(slug));
        if (!Directory.Exists(root))
        {
            Interlocked.Exchange(ref _count, 0);
            Interlocked.Exchange(ref _bytes, 0);
            return;
        }

        long count = 0;
        long bytes = 0;
        foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            count++;
            bytes += new FileInfo(file).Length;
        }

        Interlocked.Exchange(ref _count, count);
        Interlocked.Exchange(ref _bytes, bytes);
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
