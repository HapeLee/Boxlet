using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Forms;

namespace Boxlet.Launcher
{
    // Uses the .NET Framework already included in the supported Windows 11 versions.
    // The WinUI application and all of its runtime files remain together under app/.
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            bool exiting = Array.IndexOf(args, "--exit") >= 0;
            try
            {
                string directory = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app");
                string executable = Path.Combine(directory, "Boxlet.Windows.exe");
                if (!File.Exists(executable))
                    throw new FileNotFoundException("找不到 app 文件夹中的应用，请完整解压 Boxlet 便携包。", executable);

                using (Process child = Process.Start(new ProcessStartInfo(executable)
                {
                    Arguments = string.Join(" ", Array.ConvertAll(args, QuoteArgument)),
                    WorkingDirectory = directory,
                    UseShellExecute = false
                }))
                {
                    if (child == null) throw new IOException("无法启动 Boxlet。");
                    if (!exiting) return 0;
                    return child.WaitForExit(10000) ? child.ExitCode : 124;
                }
            }
            catch (Exception error)
            {
                // Installer shutdown must never block on an error dialog.
                if (!exiting) MessageBox.Show(error.Message, "Boxlet 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        private static string QuoteArgument(string value)
        {
            StringBuilder quoted = new StringBuilder("\"");
            int slashes = 0;
            foreach (char character in value)
            {
                if (character == '\\') { slashes++; continue; }
                quoted.Append('\\', character == '"' ? slashes * 2 + 1 : slashes);
                quoted.Append(character);
                slashes = 0;
            }
            quoted.Append('\\', slashes * 2);
            return quoted.Append('"').ToString();
        }
    }
}
