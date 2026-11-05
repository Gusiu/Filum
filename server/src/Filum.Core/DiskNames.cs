using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

namespace Filum.Core;

/// <summary>
/// The spelling a directory holds for a name. NTFS ignores case when it looks a name up but
/// keeps the case a name was created with, and that spelling tells one phone path's file from
/// another's when the two differ only in case (docs/PATH-RULES.md §6).
/// </summary>
internal static class DiskNames
{
    private const int FindExInfoBasic = 1;
    private const int FindExSearchNameMatch = 0;
    private static readonly IntPtr InvalidHandle = new(-1);

    private static readonly EnumerationOptions AnySpelling = new()
    {
        MatchCasing = MatchCasing.CaseInsensitive,
        AttributesToSkip = 0,
        IgnoreInaccessible = true,
    };

    /// <summary>
    /// The name <paramref name="directory"/> holds for <paramref name="name"/>, file or
    /// directory, or null when it holds no such entry. A name stored by the path rules holds no
    /// wildcard: §5 encodes * ? &lt; &gt; and ".
    /// </summary>
    public static string? Find(string directory, string name)
    {
        if (OperatingSystem.IsWindows())
        {
            // One lookup in the directory's index. Enumerating with a pattern would read the
            // whole directory, and a manifest asks about every file in it.
            var handle = FindFirstFileEx(LongPath.Extended(Path.Combine(directory, name)),
                                         FindExInfoBasic, out var data, FindExSearchNameMatch, IntPtr.Zero, 0);
            if (handle == InvalidHandle) return null;
            FindClose(handle);
            return data.FileName;
        }

        // Elsewhere the filesystem may or may not ignore case - macOS does by default - so the
        // directory is read: the name as asked if it is there, otherwise another spelling of it.
        var extended = LongPath.Extended(directory);
        if (!Directory.Exists(extended)) return null;

        string? other = null;
        foreach (var entry in Directory.EnumerateFileSystemEntries(extended, name, AnySpelling))
        {
            var held = Path.GetFileName(entry);
            if (string.Equals(held, name, StringComparison.Ordinal)) return held;
            other ??= held;
        }

        // A case-sensitive filesystem holds another spelling as another file, not this one.
        var asked = Path.Combine(extended, name);
        return other is not null && (File.Exists(asked) || Directory.Exists(asked)) ? other : null;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct Win32FindData
    {
        public uint FileAttributes;
        public FILETIME CreationTime;
        public FILETIME LastAccessTime;
        public FILETIME LastWriteTime;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint Reserved0;
        public uint Reserved1;

        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)]
        public string FileName;

        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 14)]
        public string AlternateFileName;
    }

    [DllImport("kernel32.dll", EntryPoint = "FindFirstFileExW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr FindFirstFileEx(
        string fileName, int infoLevel, out Win32FindData findData, int searchOp, IntPtr searchFilter, int additionalFlags);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool FindClose(IntPtr handle);
}
