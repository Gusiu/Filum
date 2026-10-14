using System.Text.Json;
using System.Text.Json.Serialization;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>
/// The record accompanying a .part file. It holds the transfer's identity and nothing
/// else; the number of bytes received is deliberately NOT stored here - we take it from
/// the .part file's length, so that two sources of truth cannot disagree after an abrupt
/// shutdown (ADR-003).
/// </summary>
public sealed record PartMetadata(
    [property: JsonPropertyName("p")] string Path,
    [property: JsonPropertyName("s")] long Size,
    [property: JsonPropertyName("m")] long Mtime)
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    public static PartMetadata? Read(string metaPath)
    {
        var extended = LongPath.Extended(metaPath);
        if (!File.Exists(extended)) return null;
        try
        {
            return JsonSerializer.Deserialize<PartMetadata>(File.ReadAllText(extended), Json);
        }
        catch (JsonException)
        {
            // A corrupt record is treated as no record - the transfer starts from zero.
            return null;
        }
    }

    public void Write(string metaPath)
    {
        Directory.CreateDirectory(LongPath.Extended(System.IO.Path.GetDirectoryName(metaPath)!));
        File.WriteAllText(LongPath.Extended(metaPath), JsonSerializer.Serialize(this, Json));
    }

    public bool Matches(long size, long mtime) => Size == size && Mtime == mtime;
}

public sealed record ManifestResolution(
    long Have,
    IReadOnlyList<NeededFile> Needed,
    IReadOnlyList<RejectedFile> Rejected,
    IReadOnlyList<string> Divergent);

/// <summary>
/// Answers the question of what the server is missing by comparing against the
/// filesystem. The server keeps no content index (ADR-003) - the disk is the source of
/// truth, so a backup moved to different media, or edited by hand in Explorer, simply
/// works.
///
/// In thorough mode the client attaches a digest to each entry, and a second question
/// arises: is this really the same content as before. The integrity ledger answers it
/// without reading the copy from disk (ADR-016).
/// </summary>
public sealed class ManifestResolver(StoragePaths paths, FilumOptions options, IntegrityLedger ledger)
{
    public ManifestResolution Resolve(string slug, IReadOnlyList<ManifestEntry> entries)
    {
        var have = 0L;
        var needed = new List<NeededFile>();
        var rejected = new List<RejectedFile>();
        var divergent = new List<string>();

        foreach (var entry in entries)
        {
            if (!paths.TryResolveFile(slug, entry.Path, options.MaxPathBytes,
                                      out var absolute, out _, out var rejection))
            {
                rejected.Add(new RejectedFile(entry.Path, ErrorCodes.FromRejection(rejection)));
                continue;
            }

            var identity = FileIdentity.Read(absolute);

            if (identity is { } present)
            {
                if (present.Size == entry.Size && present.Mtime == entry.Mtime)
                {
                    // The metadata matches, so by ADR-002 this is the same file.
                    // The digest, if the client supplied one, lets us check that claim.
                    if (Diverged(slug, entry))
                    {
                        divergent.Add(entry.Path);
                        needed.Add(new NeededFile(entry.Path, 0, Versioned: true));
                    }
                    else
                    {
                        have++;
                    }
                }
                else
                {
                    // The path is occupied by different content. The transfer starts from
                    // zero, and on commit the old copy is filed into .filum/versions/ (§8.3).
                    needed.Add(new NeededFile(entry.Path, 0, Versioned: true));
                }
                continue;
            }

            needed.Add(new NeededFile(entry.Path, ResumeOffset(slug, entry)));
        }

        return new ManifestResolution(have, needed, rejected, divergent);
    }

    /// <summary>
    /// Whether the content changed despite unchanged metadata.
    ///
    /// The absence of a ledger entry is **not** evidence of divergence - the file may have
    /// entered the copy before the ledger was running, or been placed there by hand.
    /// Treating such a file as substituted would mean a false alarm on every first sync in
    /// thorough mode, which is exactly when the report needs to mean something.
    /// </summary>
    private bool Diverged(string slug, ManifestEntry entry)
    {
        if (entry.Digest is not { } reported) return false;

        var recorded = ledger.DigestOf(slug, entry.Path);
        return recorded is not null && !string.Equals(recorded, reported, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// The resume offset. An unfinished partial file counts only when it was created for
    /// the same (size, mtime) pair - otherwise we would splice two different versions of a
    /// file together, for instance when a video was still being written on the last attempt.
    /// </summary>
    private long ResumeOffset(string slug, ManifestEntry entry)
    {
        var meta = PartMetadata.Read(paths.MetaPath(slug, entry.Path));
        if (meta is null || !meta.Matches(entry.Size, entry.Mtime)) return 0;

        var part = new FileInfo(LongPath.Extended(paths.PartPath(slug, entry.Path)));
        if (!part.Exists) return 0;

        // A partial file longer than the declared size is inconsistent; start over.
        return part.Length > entry.Size ? 0 : part.Length;
    }
}
