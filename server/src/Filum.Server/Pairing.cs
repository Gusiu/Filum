using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Filum.Core;

namespace Filum.Server;

/// <summary>Ładunek kodu QR z §3.3 protokołu. Nazwy pól są jednoliterowe, żeby kod QR pozostał gęsty.</summary>
public sealed record QrPayload(
    [property: JsonPropertyName("n")] string Name,
    [property: JsonPropertyName("k")] string Key,
    [property: JsonPropertyName("t")] string Token,
    [property: JsonPropertyName("p")] int Port,
    [property: JsonPropertyName("a")] IReadOnlyList<string> Addresses,
    [property: JsonPropertyName("s")] string Service);

/// <summary>
/// Tokeny parowania: jednorazowe, wygasające po 10 minutach (§3.3).
///
/// Token jest jedynym sekretem chroniącym moment parowania — po nim tożsamość
/// przejmuje przypięty klucz klienta i token przestaje mieć znaczenie.
/// </summary>
public sealed class PairingService(TimeProvider time)
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(10);

    private readonly ConcurrentDictionary<string, DateTimeOffset> _tokens = new(StringComparer.Ordinal);

    public string Issue()
    {
        Prune();
        var token = CertificateStore.ToBase64Url(RandomNumberGenerator.GetBytes(32));
        _tokens[token] = time.GetUtcNow().Add(Lifetime);
        return token;
    }

    /// <summary>Zużywa token. Zwraca false, gdy nieznany, już użyty albo wygasły.</summary>
    public bool TryConsume(string token)
    {
        if (!_tokens.TryRemove(token, out var expiresAt)) return false;
        return expiresAt > time.GetUtcNow();
    }

    public int ActiveCount
    {
        get
        {
            Prune();
            return _tokens.Count;
        }
    }

    private void Prune()
    {
        var now = time.GetUtcNow();
        foreach (var (token, expiresAt) in _tokens)
            if (expiresAt <= now)
                _tokens.TryRemove(token, out _);
    }

    /// <summary>
    /// Buduje ciąg do zakodowania w kodzie QR. Zawiera odcisk klucza serwera, więc telefon
    /// po zeskanowaniu wie, komu ufać — i od tej chwili adres przestaje być tożsamością (ADR-006).
    /// </summary>
    public static string Encode(QrPayload payload)
    {
        var json = JsonSerializer.Serialize(payload);
        return "filum1:" + CertificateStore.ToBase64Url(Encoding.UTF8.GetBytes(json));
    }
}

/// <summary>
/// Ograniczenie częstotliwości parowania: 5 prób na 15 minut z jednego adresu (§3.3).
/// Bez tego token 32-bajtowy byłby wprawdzie nie do zgadnięcia, ale sam zasób nadawałby się
/// do zasypania serwera żądaniami.
/// </summary>
public sealed class PairingRateLimiter(TimeProvider time)
{
    private static readonly TimeSpan Window = TimeSpan.FromMinutes(15);
    private const int MaxAttempts = 5;

    private readonly ConcurrentDictionary<string, List<DateTimeOffset>> _attempts = new(StringComparer.Ordinal);

    public bool TryRegisterAttempt(string source)
    {
        var now = time.GetUtcNow();
        var bucket = _attempts.GetOrAdd(source, _ => []);

        lock (bucket)
        {
            bucket.RemoveAll(at => now - at > Window);
            if (bucket.Count >= MaxAttempts) return false;

            bucket.Add(now);
            return true;
        }
    }
}
