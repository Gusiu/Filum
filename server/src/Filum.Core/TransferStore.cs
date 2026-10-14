using System.Collections.Concurrent;
using System.Security.Cryptography;
using Filum.Protocol;

namespace Filum.Core;

public enum ChunkStatus
{
    Ok,
    PathRejected,
    OffsetMismatch,
    DigestMismatch,
    DigestRequired,
    InsufficientStorage,
    TooMuchData
}

public sealed record ChunkRequest(
    string CanonicalPath,
    long Size,
    long Mtime,
    long Offset,
    string? Digest);

public sealed record ChunkOutcome(
    ChunkStatus Status,
    long Received = 0,
    long Offset = 0,
    bool Complete = false,
    string? VersionedPath = null,
    long? ExpectedOffset = null,
    PathRejection Rejection = PathRejection.None,
    long FreeBytes = 0);

/// <summary>
/// The transfer lifecycle: a partial file in .filum/incoming/, resuming by offset, and
/// committing with versioning (protocol §5.3 and §8.2).
///
/// The files/ directory holds only complete files - the user is to see a consistent tree
/// at any moment (I3), so partial files never appear in it.
/// </summary>
public sealed class TransferStore(
    StoragePaths paths,
    FilumOptions options,
    VersionStore versions,
    SpacePolicy space,
    IntegrityLedger ledger)
{
    /// <summary>
    /// Per-file locks. Two concurrent requests for the same path would corrupt the partial
    /// file, and with the client running 3-8 transfers at once that is not hypothetical.
    /// </summary>
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _locks = new();

    public async Task<ChunkOutcome> WriteAsync(string slug, ChunkRequest request, Stream body, CancellationToken ct)
    {
        if (!paths.TryResolveFile(slug, request.CanonicalPath, options.MaxPathBytes,
                                  out var target, out var storedRelative, out var rejection))
        {
            return new ChunkOutcome(ChunkStatus.PathRejected, Rejection: rejection);
        }

        // Refused immediately, before anything reaches the disk. Accepting bytes that
        // could not be recorded in the ledger anyway would waste the transfer on both
        // sides - and on resume the client has no way to send the digest on its own.
        if (options.RequireDigest && request.Digest is null)
        {
            return new ChunkOutcome(ChunkStatus.DigestRequired);
        }

        var remaining = request.Size - request.Offset;
        if (!space.HasRoomFor(paths.Root, remaining))
        {
            return new ChunkOutcome(ChunkStatus.InsufficientStorage,
                                    FreeBytes: space.FreeBytes(paths.Root));
        }

        var partId = StoragePaths.PartId(request.CanonicalPath);
        var gate = _locks.GetOrAdd(partId, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            return await WriteLockedAsync(slug, request, body, target, storedRelative, ct)
                .ConfigureAwait(false);
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task<ChunkOutcome> WriteLockedAsync(
        string slug,
        ChunkRequest request,
        Stream body,
        string target,
        string storedRelative,
        CancellationToken ct)
    {
        var partPath = paths.PartPath(slug, request.CanonicalPath);
        var metaPath = paths.MetaPath(slug, request.CanonicalPath);
        Directory.CreateDirectory(LongPath.Extended(paths.IncomingRoot(slug)));

        if (request.Offset == 0)
        {
            Delete(partPath);
            new PartMetadata(request.CanonicalPath, request.Size, request.Mtime).Write(metaPath);
        }
        else
        {
            var meta = PartMetadata.Read(metaPath);
            if (meta is null || !meta.Matches(request.Size, request.Mtime))
            {
                // The partial file was created for a different version of the file.
                // Splicing would produce a file that never existed - start over (§5.3).
                return new ChunkOutcome(ChunkStatus.OffsetMismatch, ExpectedOffset: 0);
            }

            var current = CurrentLength(partPath);
            if (current != request.Offset)
                return new ChunkOutcome(ChunkStatus.OffsetMismatch, ExpectedOffset: current);
        }

        var limit = request.Size - request.Offset;
        long received;

        await using (var part = new FileStream(LongPath.Extended(partPath), FileMode.OpenOrCreate,
                                               FileAccess.Write, FileShare.None, 64 * 1024, useAsync: true))
        {
            part.Seek(request.Offset, SeekOrigin.Begin);
            received = await CopyBoundedAsync(body, part, limit, ct).ConfigureAwait(false);
            await part.FlushAsync(ct).ConfigureAwait(false);
        }

        // The client sent more than it declared in X-Filum-Size. Silently truncating
        // would produce a file considered complete though assembled from inconsistent
        // data - exactly the kind of silent loss this whole project exists to avoid.
        if (await HasMoreDataAsync(body, ct).ConfigureAwait(false))
        {
            TruncateTo(partPath, request.Offset);
            return new ChunkOutcome(ChunkStatus.TooMuchData, ExpectedOffset: request.Offset);
        }

        var length = request.Offset + received;
        if (length < request.Size)
            return new ChunkOutcome(ChunkStatus.Ok, received, length, Complete: false);

        return await CommitAsync(slug, request, partPath, metaPath, target, storedRelative, received, ct)
            .ConfigureAwait(false);
    }

    /// <summary>
    /// Committing the transfer (§8.2). The order of the steps matters: the move into
    /// files/ must not precede filing the old version away, or I2 would be violated.
    /// </summary>
    private async Task<ChunkOutcome> CommitAsync(
        string slug,
        ChunkRequest request,
        string partPath,
        string metaPath,
        string target,
        string storedRelative,
        long received,
        CancellationToken ct)
    {
        if (request.Digest is { } expected)
        {
            var actual = await ComputeDigestAsync(partPath, ct).ConfigureAwait(false);
            if (!string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
            {
                Delete(partPath);
                Delete(metaPath);
                return new ChunkOutcome(ChunkStatus.DigestMismatch, ExpectedOffset: 0);
            }
        }

        string? versioned = null;
        if (File.Exists(LongPath.Extended(target)))
            versioned = versions.Archive(slug, storedRelative, target);

        Directory.CreateDirectory(LongPath.Extended(Path.GetDirectoryName(target)!));
        File.Move(LongPath.Extended(partPath), LongPath.Extended(target));

        File.SetLastWriteTimeUtc(LongPath.Extended(target),
                                 DateTimeOffset.FromUnixTimeSeconds(request.Mtime).UtcDateTime);

        Delete(metaPath);

        // The ledger is appended only after the move succeeds. An entry for a file that
        // never landed on disk would be worse than no entry at all.
        if (request.Digest is { } accepted)
        {
            ledger.Append(slug, request.CanonicalPath, request.Size, request.Mtime, accepted);
        }

        return new ChunkOutcome(ChunkStatus.Ok, received, request.Size, Complete: true, VersionedPath: versioned);
    }

    /// <summary>
    /// Copies at most <paramref name="limit"/> bytes. Without the bound a client could
    /// send more than it declared in X-Filum-Size and overwrite neighbouring data.
    /// </summary>
    private static async Task<long> CopyBoundedAsync(Stream source, Stream destination, long limit, CancellationToken ct)
    {
        var buffer = new byte[64 * 1024];
        long total = 0;

        while (total < limit)
        {
            var want = (int)Math.Min(buffer.Length, limit - total);
            var read = await source.ReadAsync(buffer.AsMemory(0, want), ct).ConfigureAwait(false);
            if (read == 0) break;

            await destination.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
            total += read;
        }

        return total;
    }

    private static async Task<string> ComputeDigestAsync(string path, CancellationToken ct)
    {
        await using var stream = new FileStream(LongPath.Extended(path), FileMode.Open, FileAccess.Read,
                                                FileShare.Read, 64 * 1024, useAsync: true);
        var hash = await SHA256.HashDataAsync(stream, ct).ConfigureAwait(false);
        return Convert.ToHexStringLower(hash);
    }

    /// <summary>Whether even one byte remains in the stream beyond the declared size.</summary>
    private static async Task<bool> HasMoreDataAsync(Stream source, CancellationToken ct)
    {
        var probe = new byte[1];
        return await source.ReadAsync(probe.AsMemory(0, 1), ct).ConfigureAwait(false) > 0;
    }

    /// <summary>Rolls the partial file back to a known-good offset rather than deleting it.</summary>
    private static void TruncateTo(string path, long length)
    {
        var extended = LongPath.Extended(path);
        if (!File.Exists(extended)) return;

        using var stream = new FileStream(extended, FileMode.Open, FileAccess.Write, FileShare.None);
        if (stream.Length > length) stream.SetLength(length);
    }

    private static long CurrentLength(string path)
    {
        var info = new FileInfo(LongPath.Extended(path));
        return info.Exists ? info.Length : 0;
    }

    private static void Delete(string path)
    {
        var extended = LongPath.Extended(path);
        if (File.Exists(extended)) File.Delete(extended);
    }
}
