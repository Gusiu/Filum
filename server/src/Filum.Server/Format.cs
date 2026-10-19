using System.Globalization;

namespace Filum.Server;

/// <summary>Value formatting for the panel.</summary>
public static class Format
{
    private static readonly string[] Units = ["B", "kB", "MB", "GB", "TB", "PB"];

    public static string Bytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";

        double value = bytes;
        var unit = 0;

        while (value >= 1024 && unit < Units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return string.Create(CultureInfo.CurrentCulture, $"{value:0.#} {Units[unit]}");
    }

    /// <summary>
    /// Relative time. The panel refreshes rarely, and "12 minutes ago" carries more
    /// information than a timestamp to the second that has to be subtracted mentally.
    /// </summary>
    public static string Moment(DateTimeOffset? moment)
    {
        if (moment is not { } at) return "-";

        var elapsed = DateTimeOffset.UtcNow - at;

        return elapsed switch
        {
            { TotalMinutes: < 1 } => "just now",
            { TotalMinutes: < 60 } => $"{(int)elapsed.TotalMinutes} min ago",
            { TotalHours: < 24 } => $"{(int)elapsed.TotalHours} h ago",
            { TotalDays: < 30 } => $"{(int)elapsed.TotalDays} days ago",
            _ => at.ToLocalTime().ToString("d MMM yyyy", CultureInfo.CurrentCulture),
        };
    }
}
