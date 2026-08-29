using System.Security.Cryptography;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>
/// Kontrola integralności przechowywanych kopii (§5.5 protokołu).
///
/// To jest **jedyne** miejsce, w którym serwer liczy skróty istniejących plików.
/// Wykrywanie zmian opiera się na trójce (ścieżka, rozmiar, mtime) i tak zostaje
/// (I5, ADR-002) — weryfikacja odpowiada na inne pytanie: czy to, co leży na dysku,
/// jest nadal tym, co przyszło z telefonu. Nośnik potrafi po cichu przekłamać bajt,
/// a kopia zapasowa, która o tym nie wie, jest kopią złudzenia.
///
/// Operacja jest kosztowna i uruchamiana świadomie przez użytkownika, nigdy w cyklu
/// automatycznym.
/// </summary>
public sealed class VerifyService(StoragePaths paths, FilumOptions options)
{
    public async Task<VerifyResponse> VerifyAsync(
        string slug,
        IReadOnlyList<VerifyEntry> files,
        CancellationToken ct)
    {
        var ok = new List<string>();
        var mismatch = new List<string>();
        var missing = new List<string>();

        foreach (var entry in files)
        {
            ct.ThrowIfCancellationRequested();

            if (!paths.TryResolveFile(slug, entry.Path, options.MaxPathBytes,
                                      out var absolute, out _, out _))
            {
                // Ścieżka nie do przyjęcia nigdy nie mogła trafić na dysk, więc
                // z punktu widzenia telefonu jest po prostu nieobecna.
                missing.Add(entry.Path);
                continue;
            }

            var extended = LongPath.Extended(absolute);
            if (!File.Exists(extended))
            {
                missing.Add(entry.Path);
                continue;
            }

            var actual = await ComputeAsync(extended, ct).ConfigureAwait(false);
            (string.Equals(actual, entry.Digest, StringComparison.OrdinalIgnoreCase) ? ok : mismatch)
                .Add(entry.Path);
        }

        return new VerifyResponse(ok, mismatch, missing);
    }

    private static async Task<string> ComputeAsync(string extendedPath, CancellationToken ct)
    {
        await using var stream = new FileStream(extendedPath, FileMode.Open, FileAccess.Read,
                                                FileShare.Read, 64 * 1024, useAsync: true);
        return Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, ct).ConfigureAwait(false));
    }
}
