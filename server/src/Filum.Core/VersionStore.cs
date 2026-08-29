using System.Globalization;

namespace Filum.Core;

/// <summary>
/// Odkładanie poprzedniej zawartości pliku (§8.3 protokołu, ADR-007).
///
/// Nadpisanie jest formą utraty danych, więc sprzeczne z niezmiennikiem I1.
/// Wersje trzymamy poza katalogiem files/, żeby żywe drzewo pozostało czystym
/// lustrem obecnego stanu telefonu i dało się przeglądać w Eksploratorze.
/// </summary>
public sealed class VersionStore(StoragePaths paths)
{
    /// <summary>
    /// Przenosi istniejący plik docelowy do .filum/versions/. Znacznik czasu w nazwie
    /// pochodzi z mtime **starej kopii**, nie z chwili wykonania operacji — dzięki temu
    /// nazwa mówi, z kiedy pochodzi zawartość, a nie kiedy przypadkiem doszło do wysyłki.
    /// </summary>
    /// <returns>Ścieżka wersji względem katalogu urządzenia, do zwrócenia klientowi.</returns>
    public string Archive(string slug, string storedRelativePath, string absoluteTarget)
    {
        var extended = LongPath.Extended(absoluteTarget);
        var info = new FileInfo(extended);
        if (!info.Exists)
            throw new InvalidOperationException($"Nie ma czego wersjonować: {absoluteTarget}");

        var timestamp = info.LastWriteTimeUtc.ToString("yyyyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture);

        var relativeDirectory = Path.GetDirectoryName(storedRelativePath.Replace('/', Path.DirectorySeparatorChar))
                                ?? string.Empty;
        var fileName = Path.GetFileName(storedRelativePath);
        var stem = Path.GetFileNameWithoutExtension(fileName);
        var extension = Path.GetExtension(fileName);

        var targetDirectory = Path.Combine(paths.VersionsRoot(slug), relativeDirectory);
        Directory.CreateDirectory(LongPath.Extended(targetDirectory));

        var candidate = Path.Combine(targetDirectory, $"{stem}.{timestamp}{extension}");

        // Kolizja oznacza, że mamy już odłożoną wersję o dokładnie tym mtime. Nie wolno
        // jej nadpisać, bo to znowu byłaby utrata danych — dokładamy licznik.
        var counter = 2;
        while (File.Exists(LongPath.Extended(candidate)))
        {
            candidate = Path.Combine(targetDirectory, $"{stem}.{timestamp}-{counter}{extension}");
            counter++;
        }

        File.Move(extended, LongPath.Extended(candidate));

        return Path.GetRelativePath(paths.DeviceRoot(slug), candidate)
                   .Replace(Path.DirectorySeparatorChar, '/');
    }
}
