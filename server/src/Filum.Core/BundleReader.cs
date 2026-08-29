using System.Buffers.Binary;
using System.Text.Json;
using System.Text.Json.Serialization;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>Nagłówek pojedynczego pliku w paczce (§5.4 protokołu).</summary>
public sealed record BundleEntryHeader(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("s")] long Size,
    [property: JsonPropertyName("m")] long Mtime,
    [property: JsonPropertyName("d")] string Digest);

public sealed record BundleEntryResult(string Path, bool Ok, string? Error = null, string? Versioned = null);

/// <summary>Paczka odrzucona w całości — dalsze czytanie strumienia nie ma sensu.</summary>
public sealed class BundleFormatException(string message) : Exception(message);

/// <summary>
/// Odczyt paczki małych plików (§5.4 protokołu, ADR-008).
///
/// Przy stu tysiącach plików, z których większość ma poniżej dwustu kilobajtów,
/// narzut pojedynczego żądania HTTP zaczyna dominować nad transferem danych.
/// Paczka przenosi wiele plików jednym żądaniem.
///
/// Ramkowanie jest binarne i strumieniowe: czytamy nagłówek, potem dokładnie tyle
/// bajtów, ile zapowiada, i tak w kółko. Nigdy nie trzymamy całej paczki w pamięci,
/// bo osiem megabajtów na żądanie razy kilka równoległych połączeń to już liczba,
/// której nie chcemy mnożyć bez potrzeby.
/// </summary>
public sealed class BundleReader(TransferStore transfers, FilumOptions options)
{
    /// <summary>Górna granica nagłówka. Ścieżka ma limit 4 KiB, reszta to kilkadziesiąt bajtów.</summary>
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
                    $"Paczka zawiera więcej niż {options.BundleMaxCount} plików.");

            if (header.Size < 0 || header.Size > options.BundleMaxBytes)
                throw new BundleFormatException($"Niepoprawny rozmiar pliku {header.Path}.");

            consumed += header.Size;
            if (consumed > options.BundleMaxBytes)
                throw new BundleFormatException(
                    $"Paczka przekracza {options.BundleMaxBytes} bajtów.");

            results.Add(await StoreAsync(slug, header, body, ct).ConfigureAwait(false));
        }

        return results;
    }

    /// <summary>
    /// Zapisuje jeden plik z paczki.
    ///
    /// Przechodzi przez zwykły <see cref="TransferStore"/>, więc obowiązuje ta sama
    /// ścieżka co przy pojedynczym PUT: sprawdzenie skrótu, odłożenie poprzedniej
    /// wersji, atomowe przeniesienie. Paczka jest optymalizacją transportu, nie
    /// drugim sposobem składowania.
    /// </summary>
    private async Task<BundleEntryResult> StoreAsync(
        string slug, BundleEntryHeader header, Stream body, CancellationToken ct)
    {
        // Strumień ograniczony do zapowiedzianej długości: TransferStore czyta z niego
        // jak z ciała żądania i nie ma szansy sięgnąć po bajty następnego pliku.
        var payload = new BoundedStream(body, header.Size);

        var request = new ChunkRequest(header.Path, header.Size, header.Mtime, Offset: 0, header.Digest);
        var outcome = await transfers.WriteAsync(slug, request, payload, ct).ConfigureAwait(false);

        // Cokolwiek się stało, resztę tego pliku trzeba pochłonąć — inaczej kolejny
        // nagłówek zaczęlibyśmy czytać od środka cudzych danych.
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

    /// <summary>Zwraca null, gdy strumień się skończył na granicy pliku — to poprawny koniec paczki.</summary>
    private static async Task<BundleEntryHeader?> ReadHeaderAsync(Stream body, CancellationToken ct)
    {
        var lengthBytes = new byte[4];
        var read = await ReadExactlyOrNothingAsync(body, lengthBytes, ct).ConfigureAwait(false);
        if (read == 0) return null;
        if (read != 4) throw new BundleFormatException("Paczka urwała się w nagłówku długości.");

        var length = BinaryPrimitives.ReadUInt32BigEndian(lengthBytes);
        if (length == 0 || length > MaxHeaderBytes)
            throw new BundleFormatException($"Nagłówek pliku ma niedorzeczną długość {length}.");

        var json = new byte[length];
        await body.ReadExactlyAsync(json, ct).ConfigureAwait(false);

        BundleEntryHeader? header;
        try
        {
            header = JsonSerializer.Deserialize<BundleEntryHeader>(json);
        }
        catch (JsonException e)
        {
            throw new BundleFormatException($"Nagłówek pliku nie jest poprawnym JSON-em: {e.Message}");
        }

        if (header is null || string.IsNullOrEmpty(header.Path))
            throw new BundleFormatException("Nagłówek pliku nie zawiera ścieżki.");

        // Skrót jest w paczce wymagany (§5.4): pliki są małe, więc jego policzenie
        // nic nie kosztuje, a bez niego nie mielibyśmy jak wykryć przesunięcia ramek.
        if (header.Digest.Length != 64 || !header.Digest.All(Uri.IsHexDigit))
            throw new BundleFormatException($"Brak poprawnego skrótu dla {header.Path}.");

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
/// Widok na fragment strumienia o znanej długości.
///
/// Potrzebny, bo ciało żądania jest jednym ciągiem bajtów, a każdy plik w paczce musi
/// dostać własny strumień kończący się dokładnie tam, gdzie kończy się jego zawartość.
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

    /// <summary>Pochłania niewykorzystaną resztę, żeby następny nagłówek zaczął się we właściwym miejscu.</summary>
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
