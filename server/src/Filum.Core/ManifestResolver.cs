using System.Text.Json;
using System.Text.Json.Serialization;
using Filum.Protocol;

namespace Filum.Core;

/// <summary>
/// Zapis towarzyszący plikowi .part. Trzyma wyłącznie tożsamość transferu; liczba
/// odebranych bajtów celowo NIE jest tu zapisywana — bierzemy ją z długości pliku .part,
/// żeby dwa źródła prawdy nie mogły się rozjechać po nagłym wyłączeniu (ADR-003).
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
            // Uszkodzony zapis traktujemy jak jego brak — transfer zacznie się od zera.
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
/// Odpowiada na pytanie „czego wam brakuje" przez porównanie z systemem plików.
/// Serwer nie utrzymuje indeksu zawartości (ADR-003) — źródłem prawdy jest dysk,
/// więc backup przeniesiony na inny nośnik albo ręcznie zmieniony w Eksploratorze
/// po prostu działa.
///
/// W trybie dokładnym klient dokłada do pozycji skrót, a wtedy dochodzi drugie
/// pytanie: czy to na pewno ta sama zawartość, co poprzednio. Rejestr integralności
/// pozwala odpowiedzieć bez czytania kopii z dysku (ADR-016).
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
                    // Metadane się zgadzają, więc według ADR-002 plik jest ten sam.
                    // Skrót — jeśli klient go podał — pozwala to zdanie sprawdzić.
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
                    // Ścieżka zajęta przez inną zawartość. Transfer zaczyna się od zera,
                    // a przy domknięciu stara kopia trafi do .filum/versions/ (§8.3).
                    needed.Add(new NeededFile(entry.Path, 0, Versioned: true));
                }
                continue;
            }

            needed.Add(new NeededFile(entry.Path, ResumeOffset(slug, entry)));
        }

        return new ManifestResolution(have, needed, rejected, divergent);
    }

    /// <summary>
    /// Czy zawartość zmieniła się mimo niezmienionych metadanych.
    ///
    /// Brak wpisu w rejestrze **nie** jest przesłanką rozbieżności — plik mógł trafić
    /// do kopii przed włączeniem rejestru albo zostać do niej wstawiony ręcznie.
    /// Uznanie takiego pliku za podmieniony oznaczałoby fałszywy alarm przy każdej
    /// pierwszej synchronizacji w trybie dokładnym.
    /// </summary>
    private bool Diverged(string slug, ManifestEntry entry)
    {
        if (entry.Digest is not { } reported) return false;

        var recorded = ledger.DigestOf(slug, entry.Path);
        return recorded is not null && !string.Equals(recorded, reported, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Przesunięcie wznowienia. Niedokończony fragment liczy się tylko wtedy, gdy powstał
    /// dla tej samej pary (rozmiar, mtime) — inaczej skleilibyśmy dwie różne wersje pliku,
    /// np. gdy nagranie wideo było w trakcie zapisu przy poprzedniej próbie.
    /// </summary>
    private long ResumeOffset(string slug, ManifestEntry entry)
    {
        var meta = PartMetadata.Read(paths.MetaPath(slug, entry.Path));
        if (meta is null || !meta.Matches(entry.Size, entry.Mtime)) return 0;

        var part = new FileInfo(LongPath.Extended(paths.PartPath(slug, entry.Path)));
        if (!part.Exists) return 0;

        // Fragment dłuższy niż deklarowany plik jest niespójny; każemy zacząć od nowa.
        return part.Length > entry.Size ? 0 : part.Length;
    }
}
