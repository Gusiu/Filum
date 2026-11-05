using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Filum.Core;

/// <summary>
/// One device's .filum/index.jsonl (docs/PATH-RULES.md §7): every stored path that
/// percent-decoding cannot turn back into the phone's path, with the path it stands for.
/// Read once, then only appended to.
///
/// The file is auxiliary, not authoritative - a complete manifest could rebuild it - but it
/// is what lets a person, or tools/verify_ledger.py, read the backup back without the server.
/// </summary>
internal sealed class PathIndex(string file)
{
    private static readonly Encoding Utf8 = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);

    /// <summary>Names stay readable in the file, diacritics included, instead of turning into \uXXXX.</summary>
    private static readonly JsonSerializerOptions Json = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    private readonly object _gate = new();
    private Dictionary<string, string>? _byPhone;

    /// <summary>
    /// Keyed the way NTFS compares names, so that a stored path counts as taken whatever
    /// spelling it is asked about in.
    /// </summary>
    private Dictionary<string, string>? _byStored;

    public string? StoredFor(string canonicalPath)
    {
        lock (_gate)
        {
            Load();
            return _byPhone!.GetValueOrDefault(canonicalPath);
        }
    }

    public string? PhonePathFor(string storedPath)
    {
        lock (_gate)
        {
            Load();
            return _byStored!.GetValueOrDefault(storedPath);
        }
    }

    /// <summary>Records a stored path; a phone path recorded already keeps its first record.</summary>
    public void Add(string canonicalPath, string storedPath, string reason)
    {
        lock (_gate)
        {
            Load();
            if (_byPhone!.ContainsKey(canonicalPath)) return;

            Directory.CreateDirectory(LongPath.Extended(Path.GetDirectoryName(file)!));
            var line = JsonSerializer.Serialize(new Entry(canonicalPath, storedPath, reason), Json);
            File.AppendAllText(LongPath.Extended(file), line + "\n", Utf8);

            _byPhone[canonicalPath] = storedPath;
            _byStored![storedPath] = canonicalPath;
        }
    }

    private void Load()
    {
        if (_byPhone is not null) return;

        _byPhone = new Dictionary<string, string>(StringComparer.Ordinal);
        _byStored = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        var extended = LongPath.Extended(file);
        if (!File.Exists(extended)) return;

        foreach (var line in File.ReadLines(extended, Utf8))
        {
            Entry? entry;
            try
            {
                entry = JsonSerializer.Deserialize<Entry>(line, Json);
            }
            catch (JsonException)
            {
                // A damaged line maps nothing; the rest of the file still holds.
                continue;
            }

            if (entry is { P: { } phone, S: { } stored } && !_byPhone.ContainsKey(phone))
            {
                _byPhone[phone] = stored;
                _byStored[stored] = phone;
            }
        }
    }

    private sealed record Entry(
        [property: JsonPropertyName("p")] string? P,
        [property: JsonPropertyName("s")] string? S,
        [property: JsonPropertyName("r")] string? R);
}
