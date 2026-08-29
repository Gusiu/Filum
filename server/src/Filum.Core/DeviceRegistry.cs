using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Filum.Core;

/// <summary>
/// Zapis w <c>&lt;device-slug&gt;/.filum/device.json</c>. Zawiera wyłącznie dane jawne —
/// odcisk klucza publicznego nie jest tajemnicą, więc może leżeć w katalogu backupu.
/// </summary>
public sealed record DeviceRecord(
    [property: JsonPropertyName("pin")] string Pin,
    [property: JsonPropertyName("slug")] string Slug,
    [property: JsonPropertyName("label")] string Label,
    [property: JsonPropertyName("pairedAt")] DateTimeOffset PairedAt,
    [property: JsonPropertyName("enabled")] bool Enabled = true);

/// <summary>
/// Lista sparowanych urządzeń. Źródłem prawdy pozostaje dysk (ADR-003) — pamięć jest
/// tylko podręczna, bo odczyt musi być tani przy każdym żądaniu HTTP. Skasowanie
/// katalogu urządzenia w Eksploratorze rozparowuje je po restarcie serwera.
/// </summary>
public sealed class DeviceRegistry
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly StoragePaths _paths;
    private readonly ConcurrentDictionary<string, DeviceRecord> _byPin = new(StringComparer.Ordinal);

    public DeviceRegistry(StoragePaths paths)
    {
        _paths = paths;
        Reload();
    }

    public IReadOnlyList<DeviceRecord> All() => _byPin.Values.OrderBy(d => d.Label, StringComparer.Ordinal).ToList();

    public DeviceRecord? FindByPin(string pin) => _byPin.GetValueOrDefault(pin);

    /// <summary>Odczytuje katalog backupu od nowa. Wołane przy starcie i po zmianach z zewnątrz.</summary>
    public void Reload()
    {
        _byPin.Clear();

        var root = LongPath.Extended(_paths.Root);
        if (!Directory.Exists(root)) return;

        foreach (var directory in Directory.EnumerateDirectories(root))
        {
            var file = Path.Combine(directory, ".filum", "device.json");
            if (!File.Exists(LongPath.Extended(file))) continue;

            try
            {
                var record = JsonSerializer.Deserialize<DeviceRecord>(
                    File.ReadAllText(LongPath.Extended(file)), Json);
                if (record is not null) _byPin[record.Pin] = record;
            }
            catch (JsonException)
            {
                // Uszkodzony wpis pomijamy — urządzenie po prostu wymaga ponownego parowania.
            }
        }
    }

    /// <summary>
    /// Rejestruje urządzenie albo odświeża etykietę już znanego. Powtórne parowanie tym
    /// samym kluczem NIE tworzy drugiego katalogu — inaczej użytkownik gubiłby dostęp
    /// do dotychczasowej kopii przy każdym ponownym skanowaniu kodu QR.
    /// </summary>
    public DeviceRecord Register(string pin, string label)
    {
        if (_byPin.TryGetValue(pin, out var existing))
        {
            var refreshed = existing with { Label = label };
            Persist(refreshed);
            _byPin[pin] = refreshed;
            return refreshed;
        }

        var record = new DeviceRecord(pin, MakeSlug(label, pin), label, DateTimeOffset.UtcNow);
        Persist(record);
        _byPin[pin] = record;
        return record;
    }

    public bool SetEnabled(string pin, bool enabled)
    {
        if (!_byPin.TryGetValue(pin, out var existing)) return false;

        var updated = existing with { Enabled = enabled };
        Persist(updated);
        _byPin[pin] = updated;
        return true;
    }

    private void Persist(DeviceRecord record)
    {
        var directory = _paths.MetaRoot(record.Slug);
        Directory.CreateDirectory(LongPath.Extended(directory));
        Directory.CreateDirectory(LongPath.Extended(_paths.FilesRoot(record.Slug)));

        File.WriteAllText(LongPath.Extended(Path.Combine(directory, "device.json")),
                          JsonSerializer.Serialize(record, Json));
    }

    /// <summary>
    /// Nazwa katalogu urządzenia. Ma być czytelna w Eksploratorze (I3), więc powstaje
    /// z etykiety, a nie z odcisku klucza. Sześć znaków odcisku na końcu rozróżnia
    /// dwa telefony o tej samej nazwie.
    /// </summary>
    public static string MakeSlug(string label, string pin)
    {
        var builder = new StringBuilder(label.Length);
        var lastWasDash = false;

        foreach (var c in label.ToLowerInvariant())
        {
            if (char.IsAsciiLetterOrDigit(c))
            {
                builder.Append(c);
                lastWasDash = false;
            }
            else if (!lastWasDash && builder.Length > 0)
            {
                builder.Append('-');
                lastWasDash = true;
            }
        }

        var slug = builder.ToString().Trim('-');
        if (slug.Length > 40) slug = slug[..40].TrimEnd('-');
        if (slug.Length == 0) slug = "urzadzenie";

        var suffix = new string(pin.Where(char.IsAsciiLetterOrDigit).Take(6).ToArray()).ToLowerInvariant();
        return $"{slug}-{suffix}";
    }
}
