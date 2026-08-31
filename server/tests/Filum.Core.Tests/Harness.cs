using System.Security.Cryptography;
using System.Text;
using Filum.Core;

namespace Filum.Core.Tests;

/// <summary>Katalog tymczasowy sprzątany po teście.</summary>
public sealed class TempRoot : IDisposable
{
    public string Path { get; } = System.IO.Path.Combine(
        System.IO.Path.GetTempPath(), "filum-test-" + Guid.NewGuid().ToString("N"));

    public TempRoot() => Directory.CreateDirectory(Path);

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(Path)) Directory.Delete(Path, recursive: true);
        }
        catch (IOException)
        {
            // Katalog tymczasowy — nieudane sprzątanie nie jest powodem do wywalenia testu.
        }
    }
}

/// <summary>Złożony silnik składowania na katalogu tymczasowym, gotowy do użycia w teście.</summary>
public sealed class Harness : IDisposable
{
    public const string Slug = "telefon";

    private readonly TempRoot _root = new();

    public Harness(Action<FilumOptions>? configure = null)
    {
        Options = new FilumOptions
        {
            Root = _root.Path,

            // Testy mechaniki transferu — wznawiania, przesunięć, wersjonowania —
            // sprawdzają co innego niż politykę integralności i podawanie w każdym
            // z nich skrótu tylko zaciemniałoby intencję. Sam wymóg ma własne testy
            // w IntegrityTests, gdzie jest włączany jawnie.
            RequireDigest = false,
        };
        configure?.Invoke(Options);

        Paths = new StoragePaths(_root.Path);
        Space = new SpacePolicy(Options);
        Versions = new VersionStore(Paths);
        Ledger = new IntegrityLedger(Paths);
        Manifest = new ManifestResolver(Paths, Options, Ledger);
        Transfers = new TransferStore(Paths, Options, Versions, Space, Ledger);
    }

    public FilumOptions Options { get; }
    public StoragePaths Paths { get; }
    public SpacePolicy Space { get; }
    public VersionStore Versions { get; }
    public IntegrityLedger Ledger { get; }
    public ManifestResolver Manifest { get; }
    public TransferStore Transfers { get; }

    public string Root => _root.Path;

    public string TargetOf(string canonicalPath)
    {
        Paths.TryResolveFile(Slug, canonicalPath, Options.MaxPathBytes, out var absolute, out _, out _);
        return absolute;
    }

    /// <summary>Wysyła fragment i zwraca wynik; skrót liczony automatycznie, gdy zażądany.</summary>
    public Task<ChunkOutcome> WriteAsync(
        string canonicalPath, byte[] content, long size, long mtime, long offset, string? digest = null)
    {
        var request = new ChunkRequest(canonicalPath, size, mtime, offset, digest);
        return Transfers.WriteAsync(Slug, request, new MemoryStream(content), CancellationToken.None);
    }

    /// <summary>Wysyła cały plik w jednym kawałku.</summary>
    public Task<ChunkOutcome> WriteWholeAsync(string canonicalPath, byte[] content, long mtime, bool withDigest = false)
        => WriteAsync(canonicalPath, content, content.Length, mtime, 0, withDigest ? Digest(content) : null);

    /// <summary>Kładzie plik bezpośrednio na dysku, z pominięciem transferu — do przygotowania stanu.</summary>
    public void Seed(string canonicalPath, byte[] content, long mtime)
    {
        var target = TargetOf(canonicalPath);
        Directory.CreateDirectory(LongPath.Extended(System.IO.Path.GetDirectoryName(target)!));
        File.WriteAllBytes(LongPath.Extended(target), content);
        File.SetLastWriteTimeUtc(LongPath.Extended(target), DateTimeOffset.FromUnixTimeSeconds(mtime).UtcDateTime);
    }

    public byte[] ReadStored(string canonicalPath) => File.ReadAllBytes(LongPath.Extended(TargetOf(canonicalPath)));

    public bool StoredExists(string canonicalPath) => File.Exists(LongPath.Extended(TargetOf(canonicalPath)));

    public long StoredMtime(string canonicalPath) =>
        new DateTimeOffset(File.GetLastWriteTimeUtc(LongPath.Extended(TargetOf(canonicalPath)))).ToUnixTimeSeconds();

    public string PartPath(string canonicalPath) => Paths.PartPath(Slug, canonicalPath);

    public long PartLength(string canonicalPath)
    {
        var info = new FileInfo(LongPath.Extended(PartPath(canonicalPath)));
        return info.Exists ? info.Length : -1;
    }

    public IReadOnlyList<string> VersionFiles()
    {
        var root = LongPath.Extended(Paths.VersionsRoot(Slug));
        return Directory.Exists(root)
            ? Directory.GetFiles(root, "*", SearchOption.AllDirectories)
                       .Select(file => System.IO.Path.GetFileName(file)!)
                       .OrderBy(name => name, StringComparer.Ordinal)
                       .ToList()
            : [];
    }

    public static byte[] Bytes(string text) => Encoding.UTF8.GetBytes(text);

    public static string Digest(byte[] content) => Convert.ToHexStringLower(SHA256.HashData(content));

    public void Dispose() => _root.Dispose();
}
