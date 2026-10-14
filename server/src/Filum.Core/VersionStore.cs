using System.Globalization;

namespace Filum.Core;

/// <summary>
/// Filing away a file's previous content (protocol §8.3, ADR-007).
///
/// Overwriting is a form of data loss, and so contradicts invariant I1. Versions live
/// outside the files/ directory so the live tree stays a clean mirror of the phone's
/// current state and remains browsable in Explorer.
/// </summary>
public sealed class VersionStore(StoragePaths paths)
{
    /// <summary>
    /// Moves the existing destination file into .filum/versions/. The timestamp in the
    /// name comes from the **old copy's** mtime, not from the moment of the operation -
    /// so the name says when the content is from, not when the upload happened to run.
    /// </summary>
    /// <returns>The version's path relative to the device directory, to return to the client.</returns>
    public string Archive(string slug, string storedRelativePath, string absoluteTarget)
    {
        var extended = LongPath.Extended(absoluteTarget);
        var info = new FileInfo(extended);
        if (!info.Exists)
            throw new InvalidOperationException($"Nothing to version: {absoluteTarget}");

        var timestamp = info.LastWriteTimeUtc.ToString("yyyyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture);

        var relativeDirectory = Path.GetDirectoryName(storedRelativePath.Replace('/', Path.DirectorySeparatorChar))
                                ?? string.Empty;
        var fileName = Path.GetFileName(storedRelativePath);
        var stem = Path.GetFileNameWithoutExtension(fileName);
        var extension = Path.GetExtension(fileName);

        var targetDirectory = Path.Combine(paths.VersionsRoot(slug), relativeDirectory);
        Directory.CreateDirectory(LongPath.Extended(targetDirectory));

        var candidate = Path.Combine(targetDirectory, $"{stem}.{timestamp}{extension}");

        // A collision means a version with exactly this mtime is already filed away.
        // Overwriting it would be data loss again, so we append a counter.
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
