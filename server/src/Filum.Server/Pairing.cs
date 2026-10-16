using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Filum.Core;

namespace Filum.Server;

/// <summary>The QR payload from protocol §3.3. Single-letter field names keep the code dense.</summary>
public sealed record QrPayload(
    [property: JsonPropertyName("n")] string Name,
    [property: JsonPropertyName("k")] string Key,
    [property: JsonPropertyName("t")] string Token,
    [property: JsonPropertyName("p")] int Port,
    [property: JsonPropertyName("a")] IReadOnlyList<string> Addresses,
    [property: JsonPropertyName("s")] string Service);

/// <summary>
/// Pairing tokens: single-use, expiring after 10 minutes (§3.3).
///
/// The token is the only secret protecting the moment of pairing - after that the pinned
/// client key takes over as the identity and the token stops mattering.
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

    /// <summary>Consumes a token. Returns false when it is unknown, already used or expired.</summary>
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
    /// Builds the string to encode in the QR code. It carries the server's key fingerprint,
    /// so after scanning the phone knows whom to trust - and from then on the address stops
    /// being an identity (ADR-006).
    /// </summary>
    public static string Encode(QrPayload payload)
    {
        var json = JsonSerializer.Serialize(payload);
        return "filum1:" + CertificateStore.ToBase64Url(Encoding.UTF8.GetBytes(json));
    }
}

/// <summary>
/// Pairing rate limit: 5 attempts per 15 minutes from one address (§3.3).
/// Without it a 32-byte token would still be unguessable, but the resource itself would be
/// a convenient way to flood the server with requests.
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
