using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>Server configuration. The defaults match the limits from protocol §5.1.</summary>
public sealed class FilumOptions
{
    public const string SectionName = "Filum";

    /// <summary>The backup root directory. Set in configuration, with no default.</summary>
    public string Root { get; set; } = "";

    /// <summary>
    /// The directory for the server's private key, outside the backup directory - see
    /// FilumPaths. Empty means %LOCALAPPDATA%\Filum.
    /// </summary>
    public string ConfigDirectory { get; set; } = "";

    /// <summary>
    /// The listen address. mTLS has applied since stage 4, so exposure to the network is
    /// safe - without a paired key no resource other than /pair answers at all.
    /// </summary>
    public string Bind { get; set; } = "0.0.0.0";

    public int ManifestBatch { get; set; } = 2000;

    /// <summary>
    /// Files up to this size travel in bundles (ADR-008). On a phone over Wi-Fi, bundles send
    /// 1 MB files 1.5 times as fast as separate requests, and 4 MB files no faster.
    /// </summary>
    public int SmallFileThreshold { get; set; } = 1_048_576;

    public int BundleMaxBytes { get; set; } = 8_388_608;
    public int BundleMaxCount { get; set; } = 256;
    public int MaxPathBytes { get; set; } = 4096;

    /// <summary>The listening port. Also used to build the address list in the ping response.</summary>
    public int Port { get; set; } = 8765;

    /// <summary>
    /// Whether a digest is mandatory when a file is accepted (ADR-016).
    ///
    /// On by default: without a digest there is nothing to record in the integrity ledger,
    /// and a copy with no record of acceptance has limited evidentiary value. Turning it
    /// off restores the pre-ADR-016 behaviour and is intended for installations where only
    /// recovering the data matters.
    /// </summary>
    public bool RequireDigest { get; set; } = true;

    /// <summary>
    /// Advertising the service over mDNS (protocol §4.1).
    ///
    /// It can be turned off, because corporate networks often block multicast, in which
    /// case advertising only generates traffic to no effect. The phone will find the
    /// server through its stored addresses anyway.
    /// </summary>
    public bool EnableMdns { get; set; } = true;

    /// <summary>The free-space floor (§8.4): max(5 GiB, 2% of the volume's capacity).</summary>
    public long MinFreeBytes { get; set; } = 5L * 1024 * 1024 * 1024;

    public double MinFreeFraction { get; set; } = 0.02;
}

/// <summary>
/// The \\?\ prefix that bypasses the Windows 260-character path limit.
/// The path must be fully expanded beforehand - the extended form is not normalised,
/// so '.' and '..' segments inside it would never be resolved.
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

/// <summary>File identity per I5: size and modification time, with no content digest.</summary>
public readonly record struct FileIdentity(long Size, long Mtime)
{
    /// <summary>Returns null when the file is absent. mtime is truncated to whole seconds (§2).</summary>
    public static FileIdentity? Read(string absolutePath)
    {
        var info = new FileInfo(LongPath.Extended(absolutePath));
        if (!info.Exists) return null;
        return new FileIdentity(info.Length, new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeSeconds());
    }
}

/// <summary>
/// Resolves physical paths for a device. The directory layout is described in protocol §8.1.
/// This class is the only place that joins client-supplied data with a path on disk, which
/// makes it the home of the containment check.
/// </summary>
public sealed class StoragePaths(string root)
{
    private readonly string _root = Path.GetFullPath(root);

    /// <summary>Each device's index.jsonl, read on first use (docs/PATH-RULES.md §7).</summary>
    private readonly ConcurrentDictionary<string, PathIndex> _indexes = new(StringComparer.Ordinal);

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
    /// A transfer identifier derived from the canonical path. Deterministic, so resuming
    /// after a server restart finds the same file with no database involved.
    /// </summary>
    public static string PartId(string canonicalPath) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonicalPath)));

    /// <summary>
    /// Turns a canonical path into an absolute destination path.
    /// Returns false, with the reason, when the path is unfit for storage.
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

        // A path placed once keeps its place, even after the file of another spelling that
        // decided it is gone.
        var stored = IndexFor(slug).StoredFor(canonicalPath) ?? Place(slug, canonicalPath, sanitized.Stored!);

        var filesRoot = FilesRoot(slug);
        var relative = stored.Replace('/', Path.DirectorySeparatorChar);
        var candidate = Path.GetFullPath(Path.Combine(filesRoot, relative));

        // A second layer of defence. Sanitisation should already have ruled out escaping
        // the directory, but the cost of a mistake is writing an arbitrary file anywhere
        // on the disk, so we check the result - index.jsonl included, since it is a file
        // anyone with the disk can edit.
        if (!IsInside(filesRoot, candidate))
        {
            rejection = PathRejection.Traversal;
            return false;
        }

        storedRelativePath = stored;
        absolutePath = candidate;
        return true;
    }

    /// <summary>
    /// Records in index.jsonl a stored path that percent-decoding cannot turn back into its
    /// phone path: a name shortened by rule 6 of docs/PATH-RULES.md §5, or a place settled by a
    /// case collision (§6, §7). Called as a file is committed, before it lands, so that the
    /// index never lags behind what files/ holds.
    /// </summary>
    public void RecordStored(string slug, string canonicalPath, string storedRelativePath)
    {
        if (string.Equals(PathRules.Decode(storedRelativePath), canonicalPath, StringComparison.Ordinal)) return;

        var sanitized = PathRules.Sanitize(canonicalPath, int.MaxValue).Stored;
        var reason = string.Equals(storedRelativePath, sanitized, StringComparison.Ordinal) ? "truncated" : "case_collision";
        IndexFor(slug).Add(canonicalPath, storedRelativePath, reason);
    }

    /// <summary>
    /// The phone path a path stored under files/ stands for: from index.jsonl when it is
    /// there, otherwise by percent-decoding (docs/PATH-RULES.md §8).
    /// </summary>
    public string PhonePathOf(string slug, string storedRelativePath) =>
        IndexFor(slug).PhonePathFor(storedRelativePath) ?? PathRules.Decode(storedRelativePath);

    /// <summary>
    /// Where a path that index.jsonl does not name is stored (docs/PATH-RULES.md §6). Every
    /// part of it that already exists is read back in the spelling the disk holds: a file of
    /// another spelling belongs to another phone path, and this one takes the name with the
    /// collision suffix. So does a path whose name a directory holds, or that the index gives
    /// to another phone path. A path whose directories alone are spelled differently goes
    /// into them as they are, and the index keeps its own spelling.
    /// </summary>
    private string Place(string slug, string canonicalPath, string sanitized)
    {
        var segments = sanitized.Split('/');
        var held = new string[segments.Length];
        var current = FilesRoot(slug);
        var present = true;

        for (var i = 0; i < segments.Length; i++)
        {
            var name = present ? DiskNames.Find(current, segments[i]) : null;
            present = name is not null;
            held[i] = name ?? segments[i];
            current = Path.Combine(current, held[i]);
        }

        var onDisk = string.Join('/', held);
        var owner = IndexFor(slug).PhonePathFor(onDisk);
        var taken = present && (!string.Equals(onDisk, sanitized, StringComparison.Ordinal)
                                || Directory.Exists(LongPath.Extended(current)))
                    || owner is not null && !string.Equals(owner, canonicalPath, StringComparison.Ordinal);

        return taken
            ? string.Join('/', held[..^1].Append(PathRules.WithCollisionSuffix(segments[^1], canonicalPath)))
            : onDisk;
    }

    private PathIndex IndexFor(string slug) =>
        _indexes.GetOrAdd(slug, s => new PathIndex(Path.Combine(MetaRoot(s), "index.jsonl")));

    private static bool IsInside(string root, string candidate)
    {
        var normalized = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)
                         + Path.DirectorySeparatorChar;
        return candidate.StartsWith(normalized, StringComparison.OrdinalIgnoreCase);
    }
}

/// <summary>
/// The free-space threshold from §8.4. A device that by definition never deletes anything
/// would fill the system disk if not for this limit.
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

    /// <summary>Whether some margin remains after writing <paramref name="incomingBytes"/>.</summary>
    public bool HasRoomFor(string anyPathOnVolume, long incomingBytes)
    {
        var drive = DriveFor(anyPathOnVolume);
        var floor = Math.Max(options.MinFreeBytes, (long)(drive.TotalSize * options.MinFreeFraction));
        return drive.AvailableFreeSpace - incomingBytes >= floor;
    }

    private static DriveInfo DriveFor(string path) =>
        new(Path.GetPathRoot(Path.GetFullPath(path)) ?? Path.GetFullPath(path));
}
