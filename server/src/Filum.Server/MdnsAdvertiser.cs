using Filum.Core;
using Makaretu.Dns;

namespace Filum.Server;

/// <summary>
/// Rozgłaszanie usługi w sieci lokalnej przez mDNS (§4.1 protokołu).
///
/// Po co, skoro klient i tak trzyma zbiór tras i sam je odświeża (§4.4)? Bo ten
/// mechanizm ma jedną lukę: aktualizuje się wyłącznie wtedy, gdy **choć jedna**
/// zapisana trasa zadziała. Telefon, który nie był w domu przez tydzień zmiany
/// routera i nie ma sieci nakładkowej, nie ma się od czego odbić.
///
/// mDNS zamyka tę lukę: w tej samej sieci serwer da się znaleźć bez znajomości
/// jego adresu, a odcisk klucza w rekordzie TXT pozwala od razu odrzucić cudze
/// instancje, zanim w ogóle dojdzie do połączenia.
/// </summary>
public sealed class MdnsAdvertiser(
    FilumOptions options,
    CertificateStore certificates,
    ILogger<MdnsAdvertiser> logger) : IHostedService, IDisposable
{
    /// <summary>Typ usługi z §4.1. Nazwa jest częścią protokołu, nie może się zmienić.</summary>
    public const string ServiceType = "_filum._tcp";

    private MulticastService? _multicast;
    private ServiceDiscovery? _discovery;

    public string InstanceName => BuildInstanceName(certificates.Pin);

    /// <summary>
    /// Nazwa instancji: <c>filum-</c> i osiem pierwszych znaków odcisku klucza.
    ///
    /// Ten sam ciąg trafia do kodu parowania w polu <c>s</c>, więc telefon może
    /// dopasować rozgłoszenie do serwera, z którym się sparował, jeszcze zanim
    /// odczyta rekord TXT.
    /// </summary>
    public static string BuildInstanceName(string pin) => $"filum-{pin[..8]}";

    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (!options.EnableMdns)
        {
            logger.LogInformation("Filum: rozgłaszanie mDNS wyłączone w konfiguracji");
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

            logger.LogInformation("Filum: rozgłaszam {Instance}.{Type} na porcie {Port}",
                                  InstanceName, ServiceType, options.Port);
        }
        catch (Exception ex)
        {
            // Rozgłaszanie jest wygodą, nie warunkiem działania. Zapora, brak
            // uprawnień do multicastu albo sieć bez jego obsługi nie mogą
            // uniemożliwić przyjmowania kopii zapasowej.
            logger.LogWarning(ex, "Filum: nie udało się uruchomić rozgłaszania mDNS; " +
                                  "telefon nadal znajdzie serwer po zapisanych adresach");
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
