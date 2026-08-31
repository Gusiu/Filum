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
/// Cykl życia transferu: fragment w .filum/incoming/, wznawianie po przesunięciu
/// i domknięcie z wersjonowaniem (§5.3 i §8.2 protokołu).
///
/// Katalog files/ zawiera wyłącznie kompletne pliki — użytkownik ma w każdej chwili
/// oglądać spójne drzewo (I3), więc fragmenty nigdy się w nim nie pojawiają.
/// </summary>
public sealed class TransferStore(
    StoragePaths paths,
    FilumOptions options,
    VersionStore versions,
    SpacePolicy space,
    IntegrityLedger ledger)
{
    /// <summary>
    /// Blokady per plik. Dwa równoległe żądania do tej samej ścieżki uszkodziłyby fragment,
    /// a przy 3-8 jednoczesnych transferach klienta to nie jest przypadek teoretyczny.
    /// </summary>
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _locks = new();

    public async Task<ChunkOutcome> WriteAsync(string slug, ChunkRequest request, Stream body, CancellationToken ct)
    {
        if (!paths.TryResolveFile(slug, request.CanonicalPath, options.MaxPathBytes,
                                  out var target, out var storedRelative, out var rejection))
        {
            return new ChunkOutcome(ChunkStatus.PathRejected, Rejection: rejection);
        }

        // Odrzucamy od razu, zanim cokolwiek trafi na dysk. Przyjęcie bajtów, których
        // i tak nie da się wpisać do rejestru, byłoby marnowaniem transferu po obu
        // stronach — a przy wznawianiu klient nie ma jak dosłać samego skrótu.
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
                // Fragment powstał dla innej wersji pliku. Sklejenie dałoby plik,
                // który nigdy nie istniał — każemy zacząć od zera (§5.3).
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

        // Klient przysłał więcej, niż zadeklarował w X-Filum-Size. Ciche przycięcie
        // dałoby plik uznany za kompletny, choć powstał z niespójnych danych — a to
        // dokładnie ten rodzaj cichej utraty, którego cały projekt ma unikać.
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
    /// Domknięcie transferu (§8.2). Kolejność kroków jest istotna: przeniesienie do files/
    /// nie może wyprzedzić odłożenia starej wersji, bo naruszyłoby to niezmiennik I2.
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

        // Rejestr dopisujemy dopiero po udanym przeniesieniu. Wpis o pliku, który
        // ostatecznie nie wylądował na dysku, byłby gorszy niż brak wpisu.
        if (request.Digest is { } accepted)
        {
            ledger.Append(slug, request.CanonicalPath, request.Size, request.Mtime, accepted);
        }

        return new ChunkOutcome(ChunkStatus.Ok, received, request.Size, Complete: true, VersionedPath: versioned);
    }

    /// <summary>
    /// Kopiuje najwyżej <paramref name="limit"/> bajtów. Bez ograniczenia klient mógłby
    /// wysłać więcej, niż zadeklarował w X-Filum-Size, i nadpisać sąsiedztwo w pliku.
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

    /// <summary>Czy w strumieniu został jeszcze choć jeden bajt ponad zadeklarowany rozmiar.</summary>
    private static async Task<bool> HasMoreDataAsync(Stream source, CancellationToken ct)
    {
        var probe = new byte[1];
        return await source.ReadAsync(probe.AsMemory(0, 1), ct).ConfigureAwait(false) > 0;
    }

    /// <summary>Cofa fragment do znanego dobrego przesunięcia, zamiast go kasować w całości.</summary>
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
