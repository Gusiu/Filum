using System.Text;
using System.Text.Json;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>
/// The orphan report: files present on the server and no longer on the phone (§5.6,
/// ADR-015).
///
/// Since the server never deletes anything, moving files around on the phone multiplies
/// copies and the disk grows without end. The report gives the user a basis for a decision
/// - but it is **informational only**. Neither the server nor the client deletes anything
/// on its basis; that would violate invariant I1.
///
/// Orphans are a by-product of reconciliation: we compute them only for a session that
/// reported the device's complete file set (<c>mode: full</c>). An incremental session
/// knows nothing about the files it did not mention, so the difference would be meaningless.
/// </summary>
public sealed class OrphanReporter(StoragePaths paths)
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    /// <summary>
    /// Appends the paths reported in one manifest batch.
    ///
    /// Written to disk rather than kept in memory: a session covering a hundred thousand
    /// files is fifty requests spread over time, and the server has no reason to hold state
    /// between them - least of all state that would vanish on restart.
    /// </summary>
    public void RecordBatch(string slug, string session, IEnumerable<string> canonicalPaths)
    {
        var file = SessionFile(slug, session);
        Directory.CreateDirectory(LongPath.Extended(Path.GetDirectoryName(file)!));

        using var writer = new StreamWriter(
            LongPath.Extended(file), append: true, Encoding.UTF8);

        foreach (var path in canonicalPaths) writer.WriteLine(path);
    }

    /// <summary>
    /// Closes the session: compares the reported paths against the disk and writes the report.
    /// </summary>
    public OrphanReport Complete(string slug, string session)
    {
        var reported = ReadReported(slug, session);
        var orphans = new List<string>();
        long bytes = 0;

        var filesRoot = paths.FilesRoot(slug);
        var extendedRoot = LongPath.Extended(filesRoot);

        if (Directory.Exists(extendedRoot))
        {
            foreach (var absolute in Directory.EnumerateFiles(extendedRoot, "*", SearchOption.AllDirectories))
            {
                var stored = Path.GetRelativePath(extendedRoot, absolute).Replace(Path.DirectorySeparatorChar, '/');

                // We compare in canonical form, because that is all the manifest deals in;
                // what lies on disk has been through sanitisation and looks different.
                var canonical = PathRules.Decode(stored);
                if (reported.Contains(canonical)) continue;

                orphans.Add(canonical);
                bytes += new FileInfo(absolute).Length;
            }
        }

        orphans.Sort(StringComparer.Ordinal);

        var report = new OrphanReport(session, DateTimeOffset.UtcNow, orphans.Count, bytes, orphans);
        Save(slug, session, report);
        Cleanup(slug, session);

        return report;
    }

    public OrphanReport? Read(string slug, string session)
    {
        var file = LongPath.Extended(ReportFile(slug, session));
        if (!File.Exists(file)) return null;

        try
        {
            return JsonSerializer.Deserialize<OrphanReport>(File.ReadAllText(file), Json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>The most recent report, if any exists. Used by the panel.</summary>
    public OrphanReport? Latest(string slug)
    {
        var directory = LongPath.Extended(paths.MetaRoot(slug));
        if (!Directory.Exists(directory)) return null;

        var newest = Directory.EnumerateFiles(directory, "orphans-*.json")
            .Select(f => new FileInfo(f))
            .MaxBy(f => f.LastWriteTimeUtc);

        if (newest is null) return null;

        try
        {
            return JsonSerializer.Deserialize<OrphanReport>(File.ReadAllText(newest.FullName), Json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>Abandons an unfinished session - called for an incremental session.</summary>
    public void Discard(string slug, string session) => Cleanup(slug, session);

    private HashSet<string> ReadReported(string slug, string session)
    {
        var file = LongPath.Extended(SessionFile(slug, session));
        if (!File.Exists(file)) return new HashSet<string>(StringComparer.Ordinal);

        return File.ReadLines(file, Encoding.UTF8)
            .Where(line => line.Length > 0)
            .ToHashSet(StringComparer.Ordinal);
    }

    private void Save(string slug, string session, OrphanReport report)
    {
        var file = ReportFile(slug, session);
        Directory.CreateDirectory(LongPath.Extended(Path.GetDirectoryName(file)!));
        File.WriteAllText(LongPath.Extended(file), JsonSerializer.Serialize(report, Json));
    }

    private void Cleanup(string slug, string session)
    {
        var file = LongPath.Extended(SessionFile(slug, session));
        if (File.Exists(file)) File.Delete(file);
    }

    private string SessionFile(string slug, string session) =>
        Path.Combine(paths.MetaRoot(slug), "sessions", $"{Sanitize(session)}.paths");

    private string ReportFile(string slug, string session) =>
        Path.Combine(paths.MetaRoot(slug), $"orphans-{Sanitize(session)}.json");

    /// <summary>
    /// The session identifier comes from the client and ends up in a filename, so it has to
    /// be filtered. Without this, a session named <c>../../something</c> would be enough.
    /// </summary>
    private static string Sanitize(string session)
    {
        var cleaned = new string(session.Where(char.IsAsciiLetterOrDigit).Take(64).ToArray());
        return cleaned.Length > 0 ? cleaned : "session";
    }
}
