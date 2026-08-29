using System.Security.Cryptography;
using System.Text;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>Konfiguracja serwera. Wartości domyślne odpowiadają limitom z §5.1 protokołu.</summary>
public sealed class FilumOptions
{
    public const string SectionName = "Filum";

    /// <summary>Katalog główny backupu. Ustawiany w konfiguracji, bez wartości domyślnej.</summary>
    public string Root { get; set; } = "";

    /// <summary>
    /// Katalog na klucz prywatny serwera. Poza katalogiem backupu — patrz FilumPaths.
    /// Pusty oznacza %LOCALAPPDATA%\Filum.
    /// </summary>
    public string ConfigDirectory { get; set; } = "";

    /// <summary>
    /// Adres nasłuchu. Od etapu 4 obowiązuje mTLS, więc wystawienie na sieć jest
    /// bezpieczne — bez sparowanego klucza żaden zasób poza /pair nie odpowiada.
    /// </summary>
    public string Bind { get; set; } = "0.0.0.0";

    public int ManifestBatch { get; set; } = 2000;
    public int SmallFileThreshold { get; set; } = 262_144;
    public int BundleMaxBytes { get; set; } = 8_388_608;
    public int BundleMaxCount { get; set; } = 256;
    public int MaxPathBytes { get; set; } = 4096;

    /// <summary>Port nasluchu. Uzywany takze do zbudowania listy adresow w odpowiedzi na ping.</summary>
    public int Port { get; set; } = 8765;

    /// <summary>Dolny próg wolnego miejsca (§8.4): max(5 GiB, 2% pojemności wolumenu).</summary>
    public long MinFreeBytes { get; set; } = 5L * 1024 * 1024 * 1024;

    public double MinFreeFraction { get; set; } = 0.02;
}

/// <summary>
/// Prefiks \\?\ omijający limit 260 znaków na ścieżkę w Windows.
/// Ścieżka musi być wcześniej w pełni rozwinięta — postać rozszerzona nie podlega
/// normalizacji, więc segmenty '.' i '..' nie zostałyby w niej rozwiązane.
/// </summary>
public static class LongPath
{
    public static string Extended(string path)
    {
        var full = Path.GetFullPath(path);
        if (!OperatingSystem.IsWindows()) return full;
        if (full.StartsWith(@"\\?\", StringComparison.Ordinal)) return full;
        if (full.StartsWith(@"\\", StringComparison.Ordinal)) return @"\\?\UNC\" + full[2..];
        return @"\\?\" + full;
    }
}

/// <summary>Tożsamość pliku według I5: rozmiar i czas modyfikacji, bez skrótu zawartości.</summary>
public readonly record struct FileIdentity(long Size, long Mtime)
{
    /// <summary>Zwraca null, gdy pliku nie ma. mtime jest zaokrąglany do pełnych sekund (§2).</summary>
    public static FileIdentity? Read(string absolutePath)
    {
        var info = new FileInfo(LongPath.Extended(absolutePath));
        if (!info.Exists) return null;
        return new FileIdentity(info.Length, new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeSeconds());
    }
}

/// <summary>
/// Rozwiązywanie ścieżek fizycznych dla urządzenia. Układ katalogów opisuje §8.1 protokołu.
/// Klasa jest jedynym miejscem, które łączy dane od klienta ze ścieżką na dysku — dlatego
/// tutaj mieszka kontrola zawierania.
/// </summary>
public sealed class StoragePaths(string root)
{
    private readonly string _root = Path.GetFullPath(root);

    public string Root => _root;

    public string DeviceRoot(string slug) => Path.Combine(_root, slug);
    public string FilesRoot(string slug) => Path.Combine(DeviceRoot(slug), "files");
    public string MetaRoot(string slug) => Path.Combine(DeviceRoot(slug), ".filum");
    public string IncomingRoot(string slug) => Path.Combine(MetaRoot(slug), "incoming");
    public string VersionsRoot(string slug) => Path.Combine(MetaRoot(slug), "versions");

    public string PartPath(string slug, string canonicalPath) =>
        Path.Combine(IncomingRoot(slug), PartId(canonicalPath) + ".part");

    public string MetaPath(string slug, string canonicalPath) =>
        Path.Combine(IncomingRoot(slug), PartId(canonicalPath) + ".meta");

    /// <summary>
    /// Identyfikator transferu wyprowadzony ze ścieżki kanonicznej. Deterministyczny,
    /// więc wznowienie po restarcie serwera trafia w ten sam plik bez żadnej bazy danych.
    /// </summary>
    public static string PartId(string canonicalPath) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonicalPath)));

    /// <summary>
    /// Zamienia ścieżkę kanoniczną na bezwzględną ścieżkę docelową.
    /// Zwraca false wraz z powodem, gdy ścieżka nie nadaje się do składowania.
    /// </summary>
    public bool TryResolveFile(
        string slug,
        string canonicalPath,
        int maxPathBytes,
        out string absolutePath,
        out string storedRelativePath,
        out PathRejection rejection)
    {
        absolutePath = "";
        storedRelativePath = "";

        var sanitized = PathRules.Sanitize(canonicalPath, maxPathBytes);
        if (!sanitized.Ok)
        {
            rejection = sanitized.Reason;
            return false;
        }

        rejection = PathRejection.None;
        storedRelativePath = sanitized.Stored!;

        var filesRoot = FilesRoot(slug);
        var relative = storedRelativePath.Replace('/', Path.DirectorySeparatorChar);
        var candidate = Path.GetFullPath(Path.Combine(filesRoot, relative));

        // Druga warstwa obrony. Sanityzacja powinna już wykluczyć wyjście poza katalog,
        // ale koszt pomyłki to zapis dowolnego pliku na dysku, więc sprawdzamy wynik.
        if (!IsInside(filesRoot, candidate))
        {
            rejection = PathRejection.Traversal;
            return false;
        }

        absolutePath = candidate;
        return true;
    }

    private static bool IsInside(string root, string candidate)
    {
        var normalized = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)
                         + Path.DirectorySeparatorChar;
        return candidate.StartsWith(normalized, StringComparison.OrdinalIgnoreCase);
    }
}

/// <summary>
/// Próg wolnego miejsca z §8.4. Urządzenie, które z definicji nigdy niczego nie kasuje,
/// zapchałoby dysk systemowy, gdyby nie ten limit.
/// </summary>
public sealed class SpacePolicy(FilumOptions options)
{
    public long FloorBytes(string anyPathOnVolume)
    {
        var drive = DriveFor(anyPathOnVolume);
        var fraction = (long)(drive.TotalSize * options.MinFreeFraction);
        return Math.Max(options.MinFreeBytes, fraction);
    }

    public long FreeBytes(string anyPathOnVolume) => DriveFor(anyPathOnVolume).AvailableFreeSpace;

    public long TotalBytes(string anyPathOnVolume) => DriveFor(anyPathOnVolume).TotalSize;

    /// <summary>Czy po zapisaniu <paramref name="incomingBytes"/> zostanie jeszcze zapas.</summary>
    public bool HasRoomFor(string anyPathOnVolume, long incomingBytes)
    {
        var drive = DriveFor(anyPathOnVolume);
        var floor = Math.Max(options.MinFreeBytes, (long)(drive.TotalSize * options.MinFreeFraction));
        return drive.AvailableFreeSpace - incomingBytes >= floor;
    }

    private static DriveInfo DriveFor(string path) =>
        new(Path.GetPathRoot(Path.GetFullPath(path)) ?? Path.GetFullPath(path));
}
