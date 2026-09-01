using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Filum.Core;

/// <summary>
/// Stored in <c>&lt;device-slug&gt;/.filum/device.json</c>. It holds public data only — a
/// public key's fingerprint is not a secret, so it may live in the backup directory.
/// </summary>
public sealed record DeviceRecord(
    [property: JsonPropertyName("pin")] string Pin,
    [property: JsonPropertyName("slug")] string Slug,
    [property: JsonPropertyName("label")] string Label,
    [property: JsonPropertyName("pairedAt")] DateTimeOffset PairedAt,
    [property: JsonPropertyName("enabled")] bool Enabled = true);

/// <summary>
/// The list of paired devices. The disk remains the source of truth (ADR-003) — memory is
/// only a cache, because the read has to be cheap on every HTTP request. Deleting a
/// device's directory in Explorer unpairs it once the server restarts.
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

    /// <summary>Re-reads the backup directory. Called at startup and after external changes.</summary>
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
                // A corrupt entry is skipped — the device simply has to be paired again.
            }
        }
    }

    /// <summary>
    /// Registers a device or refreshes the label of a known one. Pairing again with the
    /// same key does NOT create a second directory — otherwise the user would lose access
    /// to the existing copy every time they rescanned the QR code.
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

    /// <summary>
    /// Revokes a device's access, leaving its files untouched.
    ///
    /// Only device.json is removed. The files/ directory stays, because invariant I1 holds
    /// here too — the most common reason to unpair is a lost phone, which is the moment the
    /// backup matters most.
    /// </summary>
    public bool Forget(string pin)
    {
        if (!_byPin.TryRemove(pin, out var record)) return false;

        var file = Path.Combine(_paths.MetaRoot(record.Slug), "device.json");
        var extended = LongPath.Extended(file);
        if (File.Exists(extended)) File.Delete(extended);

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
    /// The device's directory name. It has to be legible in Explorer (I3), so it is built
    /// from the label rather than from the key fingerprint. Six characters of the
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
