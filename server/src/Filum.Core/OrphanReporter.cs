using System.Text;
using System.Text.Json;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>
/// Raport sierot: pliki obecne na serwerze, a nieobecne już na telefonie (§5.6, ADR-015).
///
/// Skoro serwer nigdy nic nie kasuje, przenoszenie plików w telefonie mnoży kopie,
/// a dysk rośnie w nieskończoność. Raport daje użytkownikowi podstawę do decyzji —
/// ale **jest wyłącznie informacyjny**. Ani serwer, ani klient niczego na jego
/// podstawie nie usuwa; to naruszałoby niezmiennik I1.
///
/// Sieroty są produktem ubocznym rekoncyliacji: liczymy je tylko dla sesji, która
/// zgłosiła komplet plików urządzenia (<c>mode: full</c>). Sesja przyrostowa nie
/// wie nic o plikach, o których nie wspomniała, więc różnica nie miałaby sensu.
/// </summary>
public sealed class OrphanReporter(StoragePaths paths)
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    /// <summary>
    /// Dopisuje ścieżki zgłoszone w partii manifestu.
    ///
    /// Zapisujemy na dysk, a nie do pamięci: sesja obejmująca sto tysięcy plików
    /// to pięćdziesiąt żądań rozłożonych w czasie, a serwer nie ma powodu trzymać
    /// stanu między nimi — zwłaszcza takiego, który przepadnie przy restarcie.
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
    /// Domyka sesję: porównuje zgłoszone ścieżki z zawartością dysku i zapisuje raport.
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

                // Porównujemy w postaci kanonicznej, bo manifest operuje wyłącznie na niej;
                // to, co leży na dysku, przeszło sanityzację i wygląda inaczej.
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

    /// <summary>Najnowszy raport, jeśli jakikolwiek istnieje. Używane przez panel.</summary>
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

    /// <summary>Porzuca niedokończoną sesję — wywoływane przy sesji przyrostowej.</summary>
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
    /// Identyfikator sesji pochodzi od klienta i trafia do nazwy pliku, więc musi
    /// przejść przez sito. Bez tego wystarczyłaby sesja o nazwie <c>../../coś</c>.
    /// </summary>
    private static string Sanitize(string session)
    {
        var cleaned = new string(session.Where(char.IsAsciiLetterOrDigit).Take(64).ToArray());
        return cleaned.Length > 0 ? cleaned : "sesja";
    }
}
