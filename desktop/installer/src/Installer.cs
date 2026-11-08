using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using Microsoft.Win32;

namespace Volta.Setup
{
    /// <summary>Progress for the window: 0..1 and what is happening.</summary>
    internal delegate void Report(double fraction, string status);

    /// <summary>Where things are, read the same way Volta reads them (APPDATA first, as AppPaths.kt does).</summary>
    internal static class Places
    {
        public const string AppName = "Volta";
        public const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Volta";
        public const string SetupKey = @"Software\Volta\Setup";
        public const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        public const string UrlKey = @"Software\Classes\volta";
        public const string ProxyKey = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        public const string UninstallerName = "Uninstall Volta.exe";
        public const string ManifestName = "install.manifest";

        /// <summary>The upgrade code of the jpackage/WiX installer earlier builds made.</summary>
        public const string LegacyUpgradeCode = "{9EE47D8C-27E3-41A7-BDA1-8FA20A3D124C}";

        private static string Env(string name, Environment.SpecialFolder fallback)
        {
            var v = Environment.GetEnvironmentVariable(name);
            return string.IsNullOrWhiteSpace(v) ? Environment.GetFolderPath(fallback) : v;
        }

        /// <summary>%APPDATA%\Volta: subscriptions, settings, logs. Never touched unless the user asks.</summary>
        public static string DataDir => Path.Combine(Env("APPDATA", Environment.SpecialFolder.ApplicationData), AppName);

        public static string DefaultInstallDir =>
            Path.Combine(Env("LOCALAPPDATA", Environment.SpecialFolder.LocalApplicationData), "Programs", AppName);

        public static string StartMenuLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Volta.lnk");
        public static string DesktopLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Volta.lnk");

        public static bool IsUnder(string path, string dir)
        {
            if (string.IsNullOrEmpty(path) || string.IsNullOrEmpty(dir)) return false;
            try
            {
                string d = Path.GetFullPath(dir).TrimEnd('\\') + "\\";
                return Path.GetFullPath(path).StartsWith(d, StringComparison.OrdinalIgnoreCase);
            }
            catch { return false; }
        }

        public static bool SameDir(string a, string b)
        {
            try { return string.Equals(Path.GetFullPath(a).TrimEnd('\\'), Path.GetFullPath(b).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase); }
            catch { return false; }
        }
    }

    /// <summary>A Volta already on this computer: ours (Uninstall key) and/or the old MSI one.</summary>
    internal sealed class Existing
    {
        public string Dir;
        public string Version;
        public bool Ours;
        public readonly List<string> LegacyProducts = new List<string>();
        public readonly List<string> LegacyDirs = new List<string>();
        public bool DesktopShortcut = true, StartMenu = true, Autostart;

        public bool Any => Ours || LegacyProducts.Count > 0;

        public IEnumerable<string> AllDirs => new[] { Dir }.Concat(LegacyDirs).Where(d => !string.IsNullOrEmpty(d));

        public static Existing Detect()
        {
            var e = new Existing();
            using (var k = Registry.CurrentUser.OpenSubKey(Places.UninstallKey))
            {
                if (k != null)
                {
                    e.Dir = k.GetValue("InstallLocation") as string;
                    e.Version = k.GetValue("DisplayVersion") as string;
                    e.Ours = !string.IsNullOrEmpty(e.Dir);
                }
            }
            foreach (var code in Native.RelatedProducts(Places.LegacyUpgradeCode))
            {
                e.LegacyProducts.Add(code);
                var dir = Native.ProductInfo(code, "InstallLocation");
                if (string.IsNullOrEmpty(dir))
                {
                    var guess = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Volta");
                    if (File.Exists(Path.Combine(guess, "Volta.exe"))) dir = guess;
                }
                if (!string.IsNullOrEmpty(dir)) e.LegacyDirs.Add(dir);
                if (e.Version == null) e.Version = Native.ProductInfo(code, "VersionString");
            }
            // What the user chose last time; a fresh install starts from what is on disk now.
            using (var k = Registry.CurrentUser.OpenSubKey(Places.SetupKey))
            {
                if (k != null)
                {
                    e.DesktopShortcut = (k.GetValue("DesktopShortcut") as int? ?? 1) != 0;
                    e.StartMenu = (k.GetValue("StartMenu") as int? ?? 1) != 0;
                }
                else if (e.Any)
                {
                    e.DesktopShortcut = File.Exists(Places.DesktopLink);
                }
            }
            using (var run = Registry.CurrentUser.OpenSubKey(Places.RunKey))
            {
                e.Autostart = !string.IsNullOrEmpty(run?.GetValue("Volta") as string);
            }
            return e;
        }
    }

    /// <summary>Talks to a running Volta and makes sure none runs from the folders about to change.</summary>
    internal static class RunningVolta
    {
        /// <summary>Asks the running Volta to quit the way «Выход» does (tunnel down, proxy back). True when it agreed.</summary>
        public static bool AskToQuit()
        {
            try
            {
                var portFile = Path.Combine(Places.DataDir, "instance.port");
                if (!File.Exists(portFile)) return false;
                int port = int.Parse(File.ReadAllText(portFile).Trim());
                using (var c = new TcpClient())
                {
                    var connect = c.BeginConnect("127.0.0.1", port, null, null);
                    if (!connect.AsyncWaitHandle.WaitOne(1500)) return false;
                    c.EndConnect(connect);
                    c.ReceiveTimeout = 4000;
                    var s = c.GetStream();
                    var msg = Encoding.UTF8.GetBytes("volta-instance-v2\n--quit\n--volta-end--\n");
                    s.Write(msg, 0, msg.Length);
                    s.Flush();
                    c.Client.Shutdown(SocketShutdown.Send);
                    var reader = new StreamReader(s, Encoding.UTF8);
                    var answer = reader.ReadLine();
                    Log.Line("asked Volta to quit: " + (answer ?? "(closed)"));
                    return answer != null && answer.Trim() == "ok";
                }
            }
            catch (Exception e)
            {
                Log.Line("asking Volta to quit: " + e.Message);
                return false;
            }
        }

        /// <summary>
        /// No Volta (nor its core or helpers) runs from [dirs] afterwards: asked politely first,
        /// ended if it won't go; the user's proxy is put back if anything had to be ended.
        /// </summary>
        public static void Stop(IEnumerable<string> dirs, Report report, double at)
        {
            var list = dirs.Where(d => !string.IsNullOrEmpty(d)).ToList();
            var running = Native.ProcessesIn(list);
            bool asked = AskToQuit();
            if (running.Count == 0 && !asked) return;
            report?.Invoke(at, Text.T.StatusClosing);
            Log.Line("running from the install folder: " + string.Join(", ", running.Select(p => p.ProcessName + "#" + p.Id)));
            var until = DateTime.UtcNow.AddSeconds(asked ? 20 : 2);
            while (DateTime.UtcNow < until)
            {
                running = Native.ProcessesIn(list);
                if (running.Count == 0) break;
                Thread.Sleep(250);
            }
            bool killed = false;
            foreach (var p in Native.ProcessesIn(list))
            {
                try
                {
                    Log.Line("ending " + p.ProcessName + "#" + p.Id);
                    p.Kill();
                    p.WaitForExit(5000);
                    killed = true;
                }
                catch (Exception e) { Log.Line("could not end " + p.Id + ": " + e.Message); }
            }
            if (killed || !asked) Proxy.RestoreFromBackup();
            Thread.Sleep(300);
        }
    }

    /// <summary>The user's proxy setting back from Volta's own backup (proxy-backup.json, see SystemProxy.kt).</summary>
    internal static class Proxy
    {
        public static void RestoreFromBackup()
        {
            var file = Path.Combine(Places.DataDir, "proxy-backup.json");
            if (!File.Exists(file)) return;
            try
            {
                var root = Json.Parse(File.ReadAllText(file, Encoding.UTF8)) as Dictionary<string, object>;
                var prev = root != null && root.TryGetValue("previous", out var p) ? p as Dictionary<string, object> : null;
                var applied = Json.Str(root, "applied");
                using (var k = Registry.CurrentUser.CreateSubKey(Places.ProxyKey))
                {
                    var now = k.GetValue("ProxyServer") as string;
                    bool stillOurs = string.IsNullOrWhiteSpace(now) || now == applied;
                    if (stillOurs && prev != null)
                    {
                        int enable = Json.Int(prev, "proxyEnable") ?? 0;
                        string server = Json.Str(prev, "proxyServer");
                        string bypass = Json.Str(prev, "proxyOverride");
                        string pac = Json.Str(prev, "autoConfigUrl");
                        int flags = Json.Int(prev, "flags") ?? (1 | (enable == 1 ? 2 : 0) | (string.IsNullOrEmpty(pac) ? 0 : 4));
                        Native.SetPerConnection(flags, server, bypass, pac);
                        k.SetValue("ProxyEnable", enable, RegistryValueKind.DWord);
                        SetOrDelete(k, "ProxyServer", server);
                        SetOrDelete(k, "ProxyOverride", bypass);
                        SetOrDelete(k, "AutoConfigURL", pac);
                        Native.ProxyChanged();
                        Log.Line("proxy restored from Volta's backup (enable=" + enable + ", server=" + (server ?? "-") + ")");
                    }
                }
                File.Delete(file);
            }
            catch (Exception e) { Log.Line("proxy restore: " + e.Message); }
        }

        private static void SetOrDelete(RegistryKey k, string name, string value)
        {
            if (value == null) k.DeleteValue(name, false);
            else k.SetValue(name, value, RegistryValueKind.String);
        }
    }

    /// <summary>What the user chose (or the defaults, or what the last install chose).</summary>
    internal sealed class InstallChoice
    {
        public string Dir;
        public bool DesktopShortcut = true, StartMenu = true, Autostart;
    }

    internal static class Installer
    {
        /// <summary>The unattended install (--silent): from the command line and the in-app updater.</summary>
        public static int Run(Options opt, Payload payload, Report report)
        {
            var existing = Existing.Detect();
            var choice = DefaultChoice(existing, opt);
            try
            {
                Install(payload, existing, choice, report ?? ((f, s) => { }));
                if (opt.Launch) LaunchVolta(choice.Dir);
                return 0;
            }
            catch (Exception e)
            {
                Log.Line("install failed: " + e);
                // The updater closed Volta to get here: leave the user a word, not nothing.
                if (opt.Update) Native.Message(Text.T.ErrorTitle, Text.T.ErrorInstall + "\n\n" + e.Message + "\n\n" + Log.Path, true);
                return 1;
            }
        }

        public static InstallChoice DefaultChoice(Existing existing, Options opt)
        {
            var c = new InstallChoice
            {
                Dir = existing.Ours && !string.IsNullOrEmpty(existing.Dir) ? existing.Dir : Places.DefaultInstallDir,
                DesktopShortcut = existing.DesktopShortcut,
                StartMenu = existing.StartMenu,
                Autostart = existing.Autostart
            };
            if (!string.IsNullOrEmpty(opt.Dir)) c.Dir = opt.Dir;
            if (opt.DesktopShortcut.HasValue) c.DesktopShortcut = opt.DesktopShortcut.Value;
            if (opt.StartMenu.HasValue) c.StartMenu = opt.StartMenu.Value;
            if (opt.Autostart.HasValue) c.Autostart = opt.Autostart.Value;
            return c;
        }

        /// <summary>
        /// A folder the user picked that already holds other things gets a Volta folder of its
        /// own inside: install and uninstall then only ever touch Volta's own files.
        /// </summary>
        public static string Normalize(string dir, Existing existing)
        {
            dir = Path.GetFullPath(Environment.ExpandEnvironmentVariables(dir.Trim().Trim('"')));
            if (existing.AllDirs.Any(d => Places.SameDir(d, dir))) return dir;
            if (File.Exists(Path.Combine(dir, "Volta.exe"))) return dir;
            if (Directory.Exists(dir) && Directory.EnumerateFileSystemEntries(dir).Any()) return Path.Combine(dir, "Volta");
            return dir;
        }

        public static void Install(Payload payload, Existing existing, InstallChoice choice, Report report)
        {
            string dir = Normalize(choice.Dir, existing);
            choice.Dir = dir;
            Log.Line("install " + payload.Version + " into " + dir + " (was: " + (existing.Version ?? "none") + " at " +
                     string.Join(", ", existing.AllDirs) + ")");
            Log.Line("data folder (left alone): " + Places.DataDir);

            report(0.01, Text.T.StatusPreparing);
            Directory.CreateDirectory(dir);
            var probe = Path.Combine(dir, ".volta-write-test");
            File.WriteAllText(probe, "ok");
            File.Delete(probe);

            // 1. No Volta may run from the folders that change.
            RunningVolta.Stop(existing.AllDirs.Concat(new[] { dir }), report, 0.03);

            // 2. The old MSI install goes (its uninstall removes only its own files; never %APPDATA%\Volta).
            foreach (var code in existing.LegacyProducts)
            {
                report(0.08, Text.T.StatusOldVersion);
                RemoveLegacy(code);
            }

            // 3. The previous files of this install.
            report(0.12, Text.T.StatusRemovingOld);
            RemoveInstalledFiles(dir, keep: null);

            // 4. The new files.
            var files = Extract(payload, dir, (f) => report(0.15 + 0.78 * f, Text.T.StatusCopying));

            // 5. Windows: Apps & Features, shortcuts, volta://, autostart.
            report(0.95, Text.T.StatusShortcuts);
            string exe = Path.Combine(dir, "Volta.exe");
            if (!File.Exists(exe)) throw new IOException("Volta.exe is missing after copying: " + exe);
            Register(dir, payload, files);
            ApplyShortcuts(dir, exe, choice);
            RegisterUrl(exe);
            SetAutostart(exe, choice.Autostart);
            using (var k = Registry.CurrentUser.CreateSubKey(Places.SetupKey))
            {
                k.SetValue("DesktopShortcut", choice.DesktopShortcut ? 1 : 0, RegistryValueKind.DWord);
                k.SetValue("StartMenu", choice.StartMenu ? 1 : 0, RegistryValueKind.DWord);
                k.SetValue("InstallDir", dir, RegistryValueKind.String);
            }
            Native.ShellChanged();
            report(1.0, Text.T.StatusDone);
            Log.Line("installed " + payload.Version + ": " + files.Count + " files");
        }

        private static void RemoveLegacy(string code)
        {
            Log.Line("removing the old MSI install " + code);
            try
            {
                var psi = new ProcessStartInfo("msiexec.exe", "/x " + code + " /qn /norestart MSIRESTARTMANAGERCONTROL=Disable")
                {
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                using (var p = Process.Start(psi))
                {
                    p.WaitForExit(180_000);
                    Log.Line("msiexec /x: " + (p.HasExited ? p.ExitCode.ToString() : "still running"));
                }
            }
            catch (Exception e) { Log.Line("msiexec: " + e.Message); }
        }

        /// <summary>
        /// Deletes exactly what an earlier install put into [dir] (its install.manifest; the
        /// known top-level items when there is none) and the folders that end up empty.
        /// </summary>
        public static void RemoveInstalledFiles(string dir, string keep)
        {
            if (!Directory.Exists(dir)) return;
            var manifest = Path.Combine(dir, Places.ManifestName);
            var items = new List<string>();
            if (File.Exists(manifest))
            {
                items.AddRange(File.ReadAllLines(manifest, Encoding.UTF8).Where(l => l.Trim().Length > 0));
            }
            else
            {
                // Volta's app folder as jpackage makes it, and nothing else.
                foreach (var top in new[] { "app", "runtime" })
                {
                    var d = Path.Combine(dir, top);
                    if (Directory.Exists(d))
                        items.AddRange(Directory.EnumerateFiles(d, "*", SearchOption.AllDirectories).Select(f => f.Substring(dir.TrimEnd('\\').Length + 1)));
                }
                items.AddRange(new[] { "Volta.exe", "Volta.ico", Places.UninstallerName });
            }
            items.Add(Places.ManifestName);
            var dirs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var rel in items)
            {
                var path = Path.Combine(dir, rel.Replace('/', '\\'));
                if (!Places.IsUnder(path, dir)) continue;
                if (keep != null && string.Equals(Path.GetFullPath(path), Path.GetFullPath(keep), StringComparison.OrdinalIgnoreCase)) continue;
                for (var parent = Path.GetDirectoryName(path); parent != null && Places.IsUnder(parent, dir); parent = Path.GetDirectoryName(parent))
                    dirs.Add(parent);
                DeleteFile(path);
            }
            foreach (var d in dirs.OrderByDescending(d => d.Length))
            {
                try { if (Directory.Exists(d) && !Directory.EnumerateFileSystemEntries(d).Any()) Directory.Delete(d); } catch { }
            }
        }

        private static void DeleteFile(string path)
        {
            for (int attempt = 0; attempt < 10; attempt++)
            {
                try
                {
                    if (!File.Exists(path)) return;
                    File.SetAttributes(path, FileAttributes.Normal);
                    File.Delete(path);
                    return;
                }
                catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
                {
                    if (attempt == 9) throw new IOException(Text.T.ErrorLocked + " " + path, e);
                    Thread.Sleep(400);
                }
            }
        }

        private static List<string> Extract(Payload payload, string dir, Action<double> progress)
        {
            var written = new List<string>();
            string root = Path.GetFullPath(dir).TrimEnd('\\') + "\\";
            using (var stream = payload.OpenZip())
            using (var zip = new ZipArchive(stream, ZipArchiveMode.Read, false, Encoding.UTF8))
            {
                long total = Math.Max(1, zip.Entries.Sum(e => e.Length));
                long done = 0;
                var buffer = new byte[256 * 1024];
                foreach (var entry in zip.Entries)
                {
                    string rel = entry.FullName.Replace('/', '\\');
                    string path = Path.GetFullPath(Path.Combine(root, rel));
                    if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new IOException("bad entry " + entry.FullName);
                    if (rel.EndsWith("\\")) { Directory.CreateDirectory(path); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(path));
                    for (int attempt = 0; ; attempt++)
                    {
                        try
                        {
                            using (var input = entry.Open())
                            using (var output = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None))
                            {
                                int n;
                                while ((n = input.Read(buffer, 0, buffer.Length)) > 0) output.Write(buffer, 0, n);
                            }
                            break;
                        }
                        catch (IOException) when (attempt < 8)
                        {
                            Thread.Sleep(500);
                        }
                    }
                    try { File.SetLastWriteTimeUtc(path, entry.LastWriteTime.UtcDateTime); } catch { }
                    written.Add(rel);
                    done += entry.Length;
                    progress((double)done / total);
                }
            }
            File.WriteAllLines(Path.Combine(dir, Places.ManifestName), written, new UTF8Encoding(false));
            return written;
        }

        private static void Register(string dir, Payload payload, List<string> files)
        {
            long bytes = 0;
            foreach (var f in files) { try { bytes += new FileInfo(Path.Combine(dir, f)).Length; } catch { } }
            string uninstaller = Path.Combine(dir, Places.UninstallerName);
            string exe = Path.Combine(dir, "Volta.exe");
            using (var k = Registry.CurrentUser.CreateSubKey(Places.UninstallKey))
            {
                k.SetValue("DisplayName", "Volta", RegistryValueKind.String);
                k.SetValue("DisplayIcon", "\"" + exe + "\",0", RegistryValueKind.String);
                k.SetValue("DisplayVersion", payload.Version, RegistryValueKind.String);
                k.SetValue("Publisher", "Volta", RegistryValueKind.String);
                k.SetValue("InstallLocation", dir, RegistryValueKind.String);
                k.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"), RegistryValueKind.String);
                k.SetValue("EstimatedSize", (int)Math.Min(int.MaxValue, bytes / 1024), RegistryValueKind.DWord);
                k.SetValue("UninstallString", "\"" + uninstaller + "\" --uninstall", RegistryValueKind.String);
                k.SetValue("QuietUninstallString", "\"" + uninstaller + "\" --uninstall --silent", RegistryValueKind.String);
                k.SetValue("URLInfoAbout", "https://github.com/colossalblend/volta", RegistryValueKind.String);
                k.SetValue("HelpLink", "https://github.com/colossalblend/volta", RegistryValueKind.String);
                k.SetValue("Comments", "VPN", RegistryValueKind.String);
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                k.SetValue("VersionCode", payload.VersionCode, RegistryValueKind.DWord);
                if (Version.TryParse(payload.Version, out var v))
                {
                    k.SetValue("VersionMajor", v.Major, RegistryValueKind.DWord);
                    k.SetValue("VersionMinor", v.Minor, RegistryValueKind.DWord);
                }
            }
        }

        private static void ApplyShortcuts(string dir, string exe, InstallChoice choice)
        {
            Shortcut(Places.StartMenuLink, exe, dir, choice.StartMenu);
            Shortcut(Places.DesktopLink, exe, dir, choice.DesktopShortcut);
        }

        private static void Shortcut(string lnk, string exe, string dir, bool wanted)
        {
            try
            {
                if (wanted) Native.CreateShortcut(lnk, exe, dir, "Volta VPN");
                else if (File.Exists(lnk) && IsOurs(Native.ShortcutTarget(lnk))) File.Delete(lnk);
            }
            catch (Exception e) { Log.Line("shortcut " + lnk + ": " + e.Message); }
        }

        /// <summary>A Volta.lnk that points at a Volta.exe (or at nothing any more) is ours to replace or remove.</summary>
        public static bool IsOurs(string target) =>
            string.IsNullOrEmpty(target) || Path.GetFileName(target).Equals("Volta.exe", StringComparison.OrdinalIgnoreCase) || !File.Exists(target);

        /// <summary>volta:// links open in Volta (the same keys Main.kt writes on every start).</summary>
        private static void RegisterUrl(string exe)
        {
            using (var k = Registry.CurrentUser.CreateSubKey(Places.UrlKey))
            {
                k.SetValue("", "URL:Volta");
                k.SetValue("URL Protocol", "");
                using (var icon = k.CreateSubKey("DefaultIcon")) icon.SetValue("", "\"" + exe + "\",0");
                using (var cmd = k.CreateSubKey(@"shell\open\command")) cmd.SetValue("", "\"" + exe + "\" \"%1\"");
            }
        }

        private static void SetAutostart(string exe, bool on)
        {
            using (var k = Registry.CurrentUser.CreateSubKey(Places.RunKey))
            {
                if (on) k.SetValue("Volta", "\"" + exe + "\" --autostart", RegistryValueKind.String);
                else k.DeleteValue("Volta", false);
            }
        }

        public static void LaunchVolta(string dir)
        {
            try
            {
                var exe = Path.Combine(Normalize(dir, new Existing()), "Volta.exe");
                Native.AllowForeground();
                Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe) });
                Log.Line("started " + exe);
            }
            catch (Exception e) { Log.Line("could not start Volta: " + e.Message); }
        }
    }

    internal static class Uninstaller
    {
        /// <summary>The unattended uninstall (--uninstall --silent).</summary>
        public static int Run(Options opt, Report report)
        {
            try
            {
                Uninstall(opt.RemoveData, opt.Dir, report ?? ((f, s) => { }));
                return 0;
            }
            catch (Exception e)
            {
                Log.Line("uninstall failed: " + e);
                return 1;
            }
        }

        /// <summary>The install to remove: --dir, the registry, or the folder this uninstaller lives in.</summary>
        public static string FindDir(string given)
        {
            if (!string.IsNullOrEmpty(given)) return Path.GetFullPath(given);
            var existing = Existing.Detect();
            if (existing.Ours) return existing.Dir;
            var here = Path.GetDirectoryName(System.Reflection.Assembly.GetExecutingAssembly().Location);
            if (File.Exists(Path.Combine(here, "Volta.exe"))) return here;
            return existing.LegacyDirs.FirstOrDefault();
        }

        public static void Uninstall(bool removeData, string givenDir, Report report)
        {
            var existing = Existing.Detect();
            string dir = FindDir(givenDir);
            Log.Line("uninstall from " + (dir ?? "(nowhere)") + ", remove data: " + removeData);

            report(0.05, Text.T.StatusClosing);
            RunningVolta.Stop(existing.AllDirs.Concat(new[] { dir }), report, 0.05);
            // Whatever ended Volta, the user's network goes back to how it was.
            Proxy.RestoreFromBackup();

            foreach (var code in existing.LegacyProducts)
            {
                report(0.2, Text.T.StatusOldVersion);
                try
                {
                    var p = Process.Start(new ProcessStartInfo("msiexec.exe", "/x " + code + " /qn /norestart") { UseShellExecute = false, CreateNoWindow = true });
                    p.WaitForExit(180_000);
                }
                catch (Exception e) { Log.Line("msiexec: " + e.Message); }
            }

            report(0.35, Text.T.StatusShortcuts);
            using (var run = Registry.CurrentUser.OpenSubKey(Places.RunKey, true))
            {
                run?.DeleteValue("Volta", false);
            }
            foreach (var lnk in new[] { Places.StartMenuLink, Places.DesktopLink })
            {
                try { if (File.Exists(lnk) && Installer.IsOurs(Native.ShortcutTarget(lnk))) File.Delete(lnk); }
                catch (Exception e) { Log.Line("shortcut " + lnk + ": " + e.Message); }
            }
            try { Registry.CurrentUser.DeleteSubKeyTree(Places.UrlKey, false); } catch (Exception e) { Log.Line("volta:// " + e.Message); }
            try { Registry.CurrentUser.DeleteSubKeyTree(Places.UninstallKey, false); } catch (Exception e) { Log.Line("uninstall key " + e.Message); }
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Volta", false); } catch { }

            report(0.55, Text.T.StatusRemovingFiles);
            string self = System.Reflection.Assembly.GetExecutingAssembly().Location;
            string leftover = null;
            if (dir != null && Directory.Exists(dir))
            {
                bool selfInside = Places.IsUnder(self, dir);
                Installer.RemoveInstalledFiles(dir, selfInside ? self : null);
                if (selfInside) leftover = MoveSelfOut(self);
                try { if (!Directory.EnumerateFileSystemEntries(dir).Any()) Directory.Delete(dir); } catch { }
            }

            if (removeData)
            {
                report(0.8, Text.T.StatusRemovingData);
                DeleteTree(Places.DataDir);
            }
            Native.ShellChanged();
            report(1.0, Text.T.StatusDone);
            Log.Line("uninstalled; data " + (removeData ? "removed" : "kept at " + Places.DataDir));
            if (leftover != null || (dir != null && Directory.Exists(dir))) CleanUpAfterExit(leftover, dir);
        }

        /// <summary>
        /// A running exe can't be deleted, but it can be renamed: the uninstaller steps out of
        /// the install folder into %TEMP% (same drive), so the folder can go now.
        /// </summary>
        private static string MoveSelfOut(string self)
        {
            try
            {
                var target = Path.Combine(Path.GetTempPath(), "Volta-uninstall-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".exe");
                if (Native.MoveFile(self, target)) return target;
            }
            catch { }
            return self;
        }

        /// <summary>Once this process has ended: its own exe (moved out or not) and the then empty install folder go.</summary>
        private static void CleanUpAfterExit(string file, string dir)
        {
            try
            {
                string Q(string p) => "'" + p.Replace("'", "''") + "'";
                var script = "Wait-Process -Id " + Process.GetCurrentProcess().Id + " -Timeout 600 -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500";
                if (file != null) script += "; Remove-Item -LiteralPath " + Q(file) + " -Force -ErrorAction SilentlyContinue";
                if (dir != null) script += "; if (Test-Path -LiteralPath " + Q(dir) + ") { if (-not (Get-ChildItem -LiteralPath " + Q(dir) + " -Force)) { Remove-Item -LiteralPath " + Q(dir) + " -Force -ErrorAction SilentlyContinue } }";
                var ps = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe");
                var encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
                Process.Start(new ProcessStartInfo(ps, "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand " + encoded)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    WorkingDirectory = Path.GetTempPath()
                });
            }
            catch (Exception e) { Log.Line("clean-up after exit: " + e.Message); }
        }

        private static void DeleteTree(string dir)
        {
            for (int attempt = 0; attempt < 6; attempt++)
            {
                try
                {
                    if (!Directory.Exists(dir)) return;
                    foreach (var f in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories))
                    {
                        try { File.SetAttributes(f, FileAttributes.Normal); } catch { }
                    }
                    Directory.Delete(dir, true);
                    return;
                }
                catch (Exception e)
                {
                    Log.Line("removing " + dir + ": " + e.Message);
                    Thread.Sleep(500);
                }
            }
        }
    }
}
