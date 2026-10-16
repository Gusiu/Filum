using System.Buffers.Binary;
using System.Text.Json;
using System.Text.Json.Serialization;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>The header of a single file inside a bundle (protocol §5.4).</summary>
public sealed record BundleEntryHeader(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("s")] long Size,
    [property: JsonPropertyName("m")] long Mtime,
    [property: JsonPropertyName("d")] string Digest);

public sealed record BundleEntryResult(string Path, bool Ok, string? Error = null, string? Versioned = null);

/// <summary>The whole bundle is rejected - reading the stream further is pointless.</summary>
public sealed class BundleFormatException(string message) : Exception(message);

/// <summary>
/// Reading a bundle of small files (protocol §5.4, ADR-008).
///
/// Across a hundred thousand files, most of them under a couple of hundred kilobytes,
/// the overhead of an individual HTTP request starts to dominate the data transfer.
/// A bundle carries many files in one request.
///
/// The framing is binary and streaming: read a header, then exactly as many bytes as it
/// announces, and repeat. We never hold a whole bundle in memory - eight megabytes per
/// request times several concurrent connections is already a number not worth
/// multiplying without cause.
/// </summary>
public sealed class BundleReader(TransferStore transfers, FilumOptions options)
{
    /// <summary>The header size ceiling. A path is capped at 4 KiB, the rest is a few dozen bytes.</summary>
    private const int MaxHeaderBytes = 8 * 1024;

    public async Task<IReadOnlyList<BundleEntryResult>> ReadAsync(
        string slug, Stream body, CancellationToken ct)
    {
        var results = new List<BundleEntryResult>();
        long consumed = 0;

        while (true)
        {
            var header = await ReadHeaderAsync(body, ct).ConfigureAwait(false);
            if (header is null) break;

            if (results.Count >= options.BundleMaxCount)
                throw new BundleFormatException(
                    $"The bundle holds more than {options.BundleMaxCount} files.");

            if (header.Size < 0 || header.Size > options.BundleMaxBytes)
                throw new BundleFormatException($"Invalid size for file {header.Path}.");

            consumed += header.Size;
            if (consumed > options.BundleMaxBytes)
                throw new BundleFormatException(
                    $"The bundle exceeds {options.BundleMaxBytes} bytes.");

            results.Add(await StoreAsync(slug, header, body, ct).ConfigureAwait(false));
        }

        return results;
    }

    /// <summary>
    /// Zapisuje jeden plik z paczki.
    ///
    /// Goes through the ordinary <see cref="TransferStore"/>, so the same path applies as
    /// for a single PUT: digest check, filing the previous version away, atomic move. A
    /// bundle is a transport optimisation, not a second way of storing things.
    /// </summary>
    private async Task<BundleEntryResult> StoreAsync(
        string slug, BundleEntryHeader header, Stream body, CancellationToken ct)
    {
        // A stream bounded to the announced length: TransferStore reads from it as it
        // would from a request body and cannot reach into the next file's bytes.
        var payload = new BoundedStream(body, header.Size);

        var request = new ChunkRequest(header.Path, header.Size, header.Mtime, Offset: 0, header.Digest);
        var outcome = await transfers.WriteAsync(slug, request, payload, ct).ConfigureAwait(false);

        // Whatever happened, the rest of this file has to be consumed - otherwise we
        // would start reading the next header from the middle of someone else's data.
        await payload.DrainAsync(ct).ConfigureAwait(false);

        return outcome.Status switch
        {
            ChunkStatus.Ok when outcome.Complete =>
                new BundleEntryResult(header.Path, true, Versioned: outcome.VersionedPath),

            ChunkStatus.Ok => new BundleEntryResult(header.Path, false, "incomplete"),
            ChunkStatus.PathRejected => new BundleEntryResult(header.Path, false, ErrorCodes.FromRejection(outcome.Rejection)),
            ChunkStatus.DigestMismatch => new BundleEntryResult(header.Path, false, ErrorCodes.DigestMismatch),
            ChunkStatus.InsufficientStorage => new BundleEntryResult(header.Path, false, ErrorCodes.InsufficientStorage),
            _ => new BundleEntryResult(header.Path, false, ErrorCodes.BadRequest),
        };
    }

    /// <summary>Returns null when the stream ended on a file boundary - a valid end of bundle.</summary>
    private static async Task<BundleEntryHeader?> ReadHeaderAsync(Stream body, CancellationToken ct)
    {
        var lengthBytes = new byte[4];
        var read = await ReadExactlyOrNothingAsync(body, lengthBytes, ct).ConfigureAwait(false);
        if (read == 0) return null;
        if (read != 4) throw new BundleFormatException("The bundle was cut off inside a length header.");

        var length = BinaryPrimitives.ReadUInt32BigEndian(lengthBytes);
        if (length == 0 || length > MaxHeaderBytes)
            throw new BundleFormatException($"A file header declares an absurd length of {length}.");

        var json = new byte[length];
        await body.ReadExactlyAsync(json, ct).ConfigureAwait(false);

        BundleEntryHeader? header;
        try
        {
            header = JsonSerializer.Deserialize<BundleEntryHeader>(json);
        }
        catch (JsonException e)
        {
            throw new BundleFormatException($"A file header is not valid JSON: {e.Message}");
        }

        if (header is null || string.IsNullOrEmpty(header.Path))
            throw new BundleFormatException("A file header carries no path.");

        // The digest is required in a bundle (§5.4): the files are small, so computing it
        // costs nothing, and without it we would have no way to detect a frame slipping.
        if (header.Digest.Length != 64 || !header.Digest.All(Uri.IsHexDigit))
            throw new BundleFormatException($"No valid digest for {header.Path}.");

        return header;
    }

    private static async Task<int> ReadExactlyOrNothingAsync(Stream stream, byte[] buffer, CancellationToken ct)
    {
        var total = 0;
        while (total < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(total), ct).ConfigureAwait(false);
            if (read == 0) break;
            total += read;
        }
        return total;
    }
}

/// <summary>
/// A view onto a fixed-length slice of a stream.
///
/// Needed because the request body is one run of bytes, while every file in a bundle has
/// to get its own stream ending exactly where its content ends.
/// </summary>
internal sealed class BoundedStream(Stream inner, long length) : Stream
{
    private long _read;

    public long Remaining => length - _read;

    public override int Read(byte[] buffer, int offset, int count) =>
        ReadAsync(buffer, offset, count, CancellationToken.None).GetAwaiter().GetResult();

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
    {
        if (Remaining <= 0) return 0;

        var want = (int)Math.Min(buffer.Length, Remaining);
        var read = await inner.ReadAsync(buffer[..want], ct).ConfigureAwait(false);
        _read += read;
        return read;
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

    /// <summary>Consumes whatever is left, so the next header starts in the right place.</summary>
    public async Task DrainAsync(CancellationToken ct)
    {
        var buffer = new byte[64 * 1024];
        while (Remaining > 0)
        {
            var read = await ReadAsync(buffer.AsMemory(), ct).ConfigureAwait(false);
            if (read == 0) break;
        }
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => length;

    public override long Position
    {
        get => _read;
        set => throw new NotSupportedException();
    }

    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}
