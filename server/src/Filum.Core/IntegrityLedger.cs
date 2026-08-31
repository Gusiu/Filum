using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Filum.Core;

/// <summary>
/// Wpis rejestru integralności — jeden przyjęty plik.
///
/// Pola są krótkie, bo rejestr rośnie o wiersz na plik i przy dużej bibliotece
/// jego rozmiar zaczyna mieć znaczenie.
/// </summary>
public sealed record LedgerEntry(
    [property: JsonPropertyName("n")] long Sequence,
    [property: JsonPropertyName("at")] long AcceptedAt,
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("s")] long Size,
    [property: JsonPropertyName("m")] long Mtime,
    [property: JsonPropertyName("d")] string Digest,
    /// <summary>Skrót poprzedniego wiersza. Spina rejestr w łańcuch (§9.2 protokołu).</summary>
    [property: JsonPropertyName("prev")] string Previous);

public sealed record LedgerVerification(
    bool Intact,
    long Entries,
    long? BrokenAtSequence,
    string? Problem);

/// <summary>
/// Rejestr integralności: dopisywany zapis wszystkiego, co serwer przyjął (ADR-016).
///
/// Odpowiada na pytanie, którego nie da się zadać samej kopii plików: **co i kiedy
/// tu trafiło, i czy od tamtej pory ktoś to ruszył**. Przy zastosowaniach dowodowych
/// to jest właściwe pytanie — samo wykrycie, że plik się zmienił, nie wystarcza,
/// jeśli nie ma czym wykazać, jak wyglądał wcześniej.
///
/// Każdy wiersz zawiera skrót wiersza poprzedniego. Dzięki temu usunięcie albo
/// podmiana dowolnego wpisu rozspójnia wszystko, co po nim następuje — a rejestr,
/// którego nie da się po cichu poprawić, jest czymś jakościowo innym niż lista skrótów.
///
/// **Czego to nie daje.** Łańcuch wykrywa edycję wsteczną, ale nie chroni przed
/// odtworzeniem całego rejestru od zera przez kogoś, kto ma dostęp do dysku
/// i do plików. Podpisanie kluczem serwera niczego by nie zmieniło, bo ten klucz
/// leży na tej samej maszynie. Uczciwa gwarancja brzmi: wykrywalność manipulacji
/// częściowej, nie niemożliwość manipulacji.
/// </summary>
public sealed class IntegrityLedger(StoragePaths paths)
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    /// <summary>
    /// UTF-8 **bez znacznika kolejności bajtów**.
    ///
    /// `Encoding.UTF8` dopisuje BOM przy tworzeniu pliku, przez co pierwszy wiersz
    /// przestaje być poprawnym JSON-em dla wszystkiego, co nie zna tej konwencji.
    /// Cała wartość tego rejestru polega na tym, że da się go sprawdzić **bez** tej
    /// aplikacji — trzema wierszami w dowolnym języku. Znacznik, który to psuje,
    /// jest wadą, a nie szczegółem zapisu.
    /// </summary>
    private static readonly Encoding Utf8 = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);

    /// <summary>Wartość pola `prev` w pierwszym wierszu. Kotwica łańcucha.</summary>
    public const string Genesis = "genesis";

    private readonly object _gate = new();

    /// <summary>
    /// Ścieżka do ostatnio przyjętego skrótu, budowane leniwie przy pierwszym pytaniu.
    ///
    /// Bez tego każde sprawdzenie przechodziłoby rejestr od początku, a partia manifestu
    /// liczy do dwóch tysięcy pozycji — przy rejestrze o stu tysiącach wpisów oznaczałoby
    /// to setki milionów odczytów wierszy na jedno żądanie.
    /// </summary>
    private readonly Dictionary<string, Dictionary<string, string>> _index = new(StringComparer.Ordinal);

    /// <summary>
    /// Dopisuje wpis o przyjętym pliku.
    ///
    /// Zapis jest pod blokadą, bo łańcuch wymaga odczytania ostatniego wiersza przed
    /// dopisaniem następnego. Przy równoległych transferach bez tego dwa wpisy
    /// wskazywałyby na tego samego poprzednika i łańcuch rozjechałby się natychmiast.
    /// </summary>
    public LedgerEntry Append(string slug, string canonicalPath, long size, long mtime, string digest)
    {
        lock (_gate)
        {
            var file = LedgerFile(slug);
            Directory.CreateDirectory(LongPath.Extended(Path.GetDirectoryName(file)!));

            var (sequence, previous) = TailOf(file);

            var entry = new LedgerEntry(
                Sequence: sequence + 1,
                AcceptedAt: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                Path: canonicalPath,
                Size: size,
                Mtime: mtime,
                Digest: digest,
                Previous: previous);

            File.AppendAllText(LongPath.Extended(file), Serialize(entry) + "\n", Utf8);
            return entry;
        }
    }

    /// <summary>Ostatni znany skrót pliku pod tą ścieżką; null, gdy nigdy nie był przyjęty.</summary>
    public string? DigestOf(string slug, string canonicalPath)
    {
        lock (_gate)
        {
            return IndexFor(slug).GetValueOrDefault(canonicalPath);
        }
    }

    /// <summary>Zrzuca zbudowany indeks; kolejne pytanie odczyta rejestr od nowa.</summary>
    public void Invalidate(string slug)
    {
        lock (_gate) _index.Remove(slug);
    }

    private Dictionary<string, string> IndexFor(string slug)
    {
        if (_index.TryGetValue(slug, out var existing)) return existing;

        var built = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var entry in Read(slug)) built[entry.Path] = entry.Digest;

        _index[slug] = built;
        return built;
    }

    /// <summary>
    /// Sprawdza spójność łańcucha.
    ///
    /// Przechodzi rejestr od początku i przelicza skróty. Wskazuje **pierwszy** wiersz,
    /// od którego coś się nie zgadza — dalsze byłyby i tak niespójne, więc podawanie
    /// ich wszystkich niczego by nie wniosło.
    /// </summary>
    public LedgerVerification Verify(string slug)
    {
        var expectedPrevious = Genesis;
        long expectedSequence = 1;
        long count = 0;

        foreach (var line in ReadLines(slug))
        {
            LedgerEntry? entry;
            try
            {
                entry = JsonSerializer.Deserialize<LedgerEntry>(line, Json);
            }
            catch (JsonException)
            {
                return new LedgerVerification(false, count, expectedSequence, "Wiersz nie da się odczytać.");
            }

            if (entry is null)
                return new LedgerVerification(false, count, expectedSequence, "Pusty wiersz rejestru.");

            if (entry.Sequence != expectedSequence)
                return new LedgerVerification(false, count, entry.Sequence,
                    $"Numer wpisu {entry.Sequence} zamiast oczekiwanego {expectedSequence} — brakuje wiersza albo został dopisany.");

            if (entry.Previous != expectedPrevious)
                return new LedgerVerification(false, count, entry.Sequence,
                    "Wpis nie wskazuje na poprzedni wiersz — rejestr był edytowany.");

            expectedPrevious = HashOf(line);
            expectedSequence++;
            count++;
        }

        return new LedgerVerification(true, count, null, null);
    }

    public IEnumerable<LedgerEntry> Read(string slug)
    {
        foreach (var line in ReadLines(slug))
        {
            LedgerEntry? entry = null;
            try
            {
                entry = JsonSerializer.Deserialize<LedgerEntry>(line, Json);
            }
            catch (JsonException)
            {
                // Uszkodzony wiersz pomijamy przy odczycie; od wykrywania takich
                // sytuacji jest Verify, a nie każdy odczyt z osobna.
            }

            if (entry is not null) yield return entry;
        }
    }

    public long Count(string slug) => ReadLines(slug).LongCount();

    private IEnumerable<string> ReadLines(string slug)
    {
        var file = LongPath.Extended(LedgerFile(slug));
        if (!File.Exists(file)) yield break;

        foreach (var line in File.ReadLines(file, Utf8))
        {
            if (line.Length > 0) yield return line;
        }
    }

    /// <summary>Numer i skrót ostatniego wiersza — punkt zaczepienia dla następnego.</summary>
    private (long Sequence, string Hash) TailOf(string file)
    {
        var extended = LongPath.Extended(file);
        if (!File.Exists(extended)) return (0, Genesis);

        string? last = null;
        foreach (var line in File.ReadLines(extended, Utf8))
        {
            if (line.Length > 0) last = line;
        }

        if (last is null) return (0, Genesis);

        var sequence = JsonSerializer.Deserialize<LedgerEntry>(last, Json)?.Sequence ?? 0;
        return (sequence, HashOf(last));
    }

    /// <summary>
    /// Skrót wiersza liczony z jego **dokładnej postaci tekstowej**.
    ///
    /// Nie z pól obiektu: gdyby serializacja kiedykolwiek zmieniła kolejność pól albo
    /// formatowanie liczb, cały dotychczasowy łańcuch stałby się niespójny bez żadnej
    /// manipulacji. Wiersz jest tym, co zapisano, i to on jest hashowany.
    /// </summary>
    private static string HashOf(string line) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(line)));

    private static string Serialize(LedgerEntry entry) => JsonSerializer.Serialize(entry, Json);

    private string LedgerFile(string slug) => Path.Combine(paths.MetaRoot(slug), "integrity.jsonl");
}
