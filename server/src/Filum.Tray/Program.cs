using System.Diagnostics;
using System.Globalization;
using Filum.Protocol;

namespace Filum.Tray;

/// <summary>
/// Ikona Filum w zasobniku systemowym.
///
/// Gives the application a physical presence in the system: at a glance the user can see
/// whether the server is running, and right-click reaches what would otherwise need hunting
/// okna - panel i katalog kopii.
/// </summary>
internal static class Program
{
    private const string ConfigEnvironmentVariable = "FILUM_CONFIG";
    private const string PortEnvironmentVariable = "FILUM_PORT";
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromSeconds(20);

    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayContext());
    }

    private sealed class TrayContext : ApplicationContext
    {
        private readonly ServerLink _link;
        private readonly NotifyIcon _icon;
        private readonly System.Windows.Forms.Timer _timer;
        private readonly ToolStripMenuItem _statusItem;
        private readonly ToolStripMenuItem _folderItem;

        private AdminStatus? _status;

        public TrayContext()
        {
            _link = new ServerLink(ConfigDirectory(), Port());

            _statusItem = new ToolStripMenuItem("Checking…") { Enabled = false };
            _folderItem = new ToolStripMenuItem("Open the backup folder", null, (_, _) => OpenBackupFolder())
            {
                Enabled = false,
            };

            var menu = new ContextMenuStrip();
            menu.Items.Add(_statusItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Open the panel", null, (_, _) => Open(_link.PanelUrl)));
            menu.Items.Add(new ToolStripMenuItem("Pair a phone", null, (_, _) => Open(_link.PairingUrl)));
            menu.Items.Add(_folderItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Close the tray icon", null, (_, _) => Quit()));

            _icon = new NotifyIcon
            {
                Icon = SystemIcons.Application,
                Text = "Filum",
                ContextMenuStrip = menu,
                Visible = true,
            };
            _icon.DoubleClick += (_, _) => Open(_link.PanelUrl);

            _timer = new System.Windows.Forms.Timer { Interval = (int)RefreshInterval.TotalMilliseconds };
            _timer.Tick += async (_, _) => await RefreshAsync();
            _timer.Start();

            _ = RefreshAsync();
        }

        private async Task RefreshAsync()
        {
            _status = await _link.StatusAsync();

            if (_status is null)
            {
                _statusItem.Text = "The server is not running";
                _folderItem.Enabled = false;
                // The icon's tooltip has a hard 63-character limit; anything longer is silently cut.
                _icon.Text = "Filum - the server is not running";
                return;
            }

            var devices = _status.Devices.Count;
            var files = _status.Devices.Sum(d => d.FileCount);

            _statusItem.Text = devices == 0
                ? "No phones paired"
                : $"{devices} {Devices(devices)}, {files} files";

            _folderItem.Enabled = Directory.Exists(_status.BackupRoot);
            _icon.Text = Truncate($"Filum - {Bytes(_status.FreeBytes)} free");
        }

        private void OpenBackupFolder()
        {
            if (_status is null || !Directory.Exists(_status.BackupRoot)) return;
            Open(_status.BackupRoot);
        }

        private static void Open(string target)
        {
            try
            {
                Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Could not open:\n{target}\n\n{ex.Message}",
                                "Filum", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void Quit()
        {
            _timer.Stop();
            _icon.Visible = false;
            ExitThread();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _timer.Dispose();
                _icon.Dispose();
                _link.Dispose();
            }
            base.Dispose(disposing);
        }

        private static string Devices(int count) => count == 1 ? "phone" : "phones";

        private static string Truncate(string text) => text.Length <= 63 ? text : text[..63];
    }

    private static string ConfigDirectory() =>
        Environment.GetEnvironmentVariable(ConfigEnvironmentVariable)
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Filum");

    private static int Port() =>
        int.TryParse(Environment.GetEnvironmentVariable(PortEnvironmentVariable), out var port) ? port : 8765;

    private static string Bytes(long bytes)
    {
        string[] units = ["B", "kB", "MB", "GB", "TB"];
        double value = bytes;
        var unit = 0;

        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return string.Create(CultureInfo.CurrentCulture, $"{value:0.#} {units[unit]}");
    }
}
