using System.Globalization;

namespace Filum.Server;

/// <summary>Formatowanie wartości na potrzeby panelu.</summary>
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
    /// Czas względny. Panel odświeża się rzadko, a „12 minut temu" niesie więcej
    /// informacji niż data z sekundami, którą trzeba w głowie odjąć od teraz.
    /// </summary>
    public static string Moment(DateTimeOffset? moment)
    {
        if (moment is not { } at) return "—";

        var elapsed = DateTimeOffset.UtcNow - at;

        return elapsed switch
        {
            { TotalMinutes: < 1 } => "przed chwilą",
            { TotalMinutes: < 60 } => $"{(int)elapsed.TotalMinutes} min temu",
            { TotalHours: < 24 } => $"{(int)elapsed.TotalHours} godz. temu",
            { TotalDays: < 30 } => $"{(int)elapsed.TotalDays} dni temu",
            _ => at.ToLocalTime().ToString("d MMM yyyy", CultureInfo.CurrentCulture),
        };
    }
}
