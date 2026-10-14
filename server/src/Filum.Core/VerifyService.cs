using System.Security.Cryptography;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>
/// Integrity checking of the stored copies (protocol §5.5).
///
/// This is the **only** place where the server hashes existing files. Change detection
/// rests on the triple (path, size, mtime) and stays that way by default (I5, ADR-002) -
/// verification answers a different question: is what lies on the disk still what arrived
/// from the phone. Storage media can silently corrupt a byte, and a backup that does not
/// know it is a backup of an illusion.
///
/// The operation is expensive and started deliberately by the user, never in an automatic
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
                // An unacceptable path could never have reached the disk, so
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
