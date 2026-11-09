using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;

namespace Volta.Setup
{
    /// <summary>
    /// Volta's installer and uninstaller in one program.
    ///
    ///   volta-…-setup.exe                       the installer, with its window
    ///   volta-…-setup.exe --silent [--dir D] [--launch] [--update] [--no-desktop-shortcut]
    ///                     [--no-start-menu] [--autostart|--no-autostart]
    ///                                           no window at all (/S works too); --update is what
    ///                                           the in-app updater passes, --launch starts Volta after
    ///   volta-…-setup.exe --passive …           only the progress, nothing to answer
    ///   Uninstall Volta.exe [--silent] [--remove-data]
    ///                                           the same program without the payload; it uninstalls
    ///   … --screenshots DIR [--lang ru|en]      every page as PNG (for CI)
    ///   … --log FILE                            where the log goes (default %TEMP%\Volta-setup.log)
    ///
    /// Exit codes: 0 done, 1 failed, 2 cancelled by the user.
    /// </summary>
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] argv)
        {
            var opt = Options.Parse(argv);
            Log.Init(opt.LogFile);
            Log.Line("Volta Setup " + Payload.SetupVersion + " started: " + string.Join(" ", argv) +
                     " (exe " + Assembly.GetExecutingAssembly().Location + ", user " + Environment.UserName + ")");
            AppDomain.CurrentDomain.UnhandledException += (s, e) => Log.Line("unhandled: " + e.ExceptionObject);
            try
            {
                Text.Choose(opt.Lang);
                if (opt.Screenshots != null) return Screens.Render(opt.Screenshots);

                var payload = Payload.Open();
                bool uninstall = opt.Uninstall || payload == null;
                if (uninstall && opt.Silent) return Uninstaller.Run(opt, null);
                if (!uninstall && opt.Silent) return Installer.Run(opt, payload, null);
                return Ui.Run(opt, payload, uninstall);
            }
            catch (Exception e)
            {
                Log.Line("failed: " + e);
                if (!opt.Silent) Native.Message(Text.T.ErrorTitle, Text.T.ErrorUnexpected + "\n\n" + e.Message + "\n\n" + Log.Path, true);
                return 1;
            }
        }
    }

    internal sealed class Options
    {
        public bool Silent, Passive, Uninstall, Launch, Update, RemoveData;
        public bool? DesktopShortcut, StartMenu, Autostart;
        public string Dir, Lang, LogFile, Screenshots;

        public static Options Parse(string[] argv)
        {
            var o = new Options();
            for (int i = 0; i < argv.Length; i++)
            {
                string a = argv[i];
                string low = a.ToLowerInvariant();
                string Next() => i + 1 < argv.Length ? argv[++i] : null;
                if (low == "/s" || low == "--silent" || low == "/silent" || low == "/quiet" || low == "/qn" || low == "--quiet") o.Silent = true;
                else if (low == "--passive" || low == "/passive") o.Passive = true;
                else if (low == "--uninstall" || low == "/uninstall" || low == "/u") o.Uninstall = true;
                else if (low == "--launch" || low == "--run") o.Launch = true;
                else if (low == "--update") o.Update = true;
                else if (low == "--remove-data") o.RemoveData = true;
                else if (low == "--keep-data") o.RemoveData = false;
                else if (low == "--desktop-shortcut") o.DesktopShortcut = true;
                else if (low == "--no-desktop-shortcut") o.DesktopShortcut = false;
                else if (low == "--start-menu") o.StartMenu = true;
                else if (low == "--no-start-menu") o.StartMenu = false;
                else if (low == "--autostart") o.Autostart = true;
                else if (low == "--no-autostart") o.Autostart = false;
                else if (low == "--dir") o.Dir = Next();
                else if (low.StartsWith("--dir=")) o.Dir = a.Substring(6);
                else if (low.StartsWith("/d=")) o.Dir = a.Substring(3);
                else if (low == "--lang") o.Lang = Next();
                else if (low == "--log") o.LogFile = Next();
                else if (low == "--screenshots") o.Screenshots = Next();
            }
            if (o.Dir != null) o.Dir = o.Dir.Trim().Trim('"');
            return o;
        }
    }

    internal static class Log
    {
        public static string Path { get; private set; }
        private static readonly object Gate = new object();

        public static void Init(string file)
        {
            Path = file ?? System.IO.Path.Combine(System.IO.Path.GetTempPath(), "Volta-setup.log");
            try
            {
                var info = new FileInfo(Path);
                if (info.Exists && info.Length > 2_000_000) info.Delete();
            }
            catch { }
        }

        public static void Line(string text)
        {
            lock (Gate)
            {
                try
                {
                    File.AppendAllText(Path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff", CultureInfo.InvariantCulture) + " " + text + Environment.NewLine, new UTF8Encoding(false));
                }
                catch { }
            }
        }
    }

    /// <summary>The app inside the installer: payload.zip (the app folder) and payload.json (its version).</summary>
    internal sealed class Payload
    {
        public string Version = "0.0.0";
        public int VersionCode;
        public long Size;
        public int Files;

        public static string SetupVersion =>
            Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0";

        public static Payload Open()
        {
            var asm = Assembly.GetExecutingAssembly();
            using (var zip = asm.GetManifestResourceStream("payload.zip"))
            {
                if (zip == null) return null;
            }
            var p = new Payload { Version = SetupVersion };
            using (var s = asm.GetManifestResourceStream("payload.json"))
            {
                if (s != null)
                {
                    using (var r = new StreamReader(s, Encoding.UTF8))
                    {
                        if (Json.Parse(r.ReadToEnd()) is Dictionary<string, object> j)
                        {
                            p.Version = Json.Str(j, "version") ?? p.Version;
                            p.VersionCode = (int)Json.Num(j, "versionCode");
                            p.Size = (long)Json.Num(j, "size");
                            p.Files = (int)Json.Num(j, "files");
                        }
                    }
                }
            }
            return p;
        }

        public Stream OpenZip() => Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip");
    }

    /// <summary>Just enough JSON for proxy-backup.json and payload.json.</summary>
    internal static class Json
    {
        public static object Parse(string text)
        {
            int i = 0;
            try { return Value(text, ref i); } catch { return null; }
        }

        public static string Str(Dictionary<string, object> o, string k) => o != null && o.TryGetValue(k, out var v) ? v as string : null;

        public static double Num(Dictionary<string, object> o, string k) =>
            o != null && o.TryGetValue(k, out var v) && v is double d ? d : 0;

        public static int? Int(Dictionary<string, object> o, string k) =>
            o != null && o.TryGetValue(k, out var v) && v is double d ? (int?)(int)d : null;

        private static void Ws(string s, ref int i) { while (i < s.Length && char.IsWhiteSpace(s[i])) i++; }

        private static object Value(string s, ref int i)
        {
            Ws(s, ref i);
            char c = s[i];
            if (c == '{')
            {
                var o = new Dictionary<string, object>();
                i++; Ws(s, ref i);
                if (s[i] == '}') { i++; return o; }
                while (true)
                {
                    Ws(s, ref i);
                    string k = (string)Value(s, ref i);
                    Ws(s, ref i); i++; // ':'
                    o[k] = Value(s, ref i);
                    Ws(s, ref i);
                    if (s[i] == ',') { i++; continue; }
                    i++; return o; // '}'
                }
            }
            if (c == '[')
            {
                var a = new List<object>();
                i++; Ws(s, ref i);
                if (s[i] == ']') { i++; return a; }
                while (true)
                {
                    a.Add(Value(s, ref i));
                    Ws(s, ref i);
                    if (s[i] == ',') { i++; continue; }
                    i++; return a;
                }
            }
            if (c == '"')
            {
                var sb = new StringBuilder();
                i++;
                while (s[i] != '"')
                {
                    if (s[i] == '\\')
                    {
                        i++;
                        char e = s[i];
                        switch (e)
                        {
                            case 'n': sb.Append('\n'); break;
                            case 'r': sb.Append('\r'); break;
                            case 't': sb.Append('\t'); break;
                            case 'b': sb.Append('\b'); break;
                            case 'f': sb.Append('\f'); break;
                            case 'u': sb.Append((char)int.Parse(s.Substring(i + 1, 4), NumberStyles.HexNumber)); i += 4; break;
                            default: sb.Append(e); break;
                        }
                        i++;
                    }
                    else sb.Append(s[i++]);
                }
                i++;
                return sb.ToString();
            }
            if (s.Substring(i).StartsWith("true")) { i += 4; return true; }
            if (s.Substring(i).StartsWith("false")) { i += 5; return false; }
            if (s.Substring(i).StartsWith("null")) { i += 4; return null; }
            int start = i;
            while (i < s.Length && "+-0123456789.eE".IndexOf(s[i]) >= 0) i++;
            return double.Parse(s.Substring(start, i - start), CultureInfo.InvariantCulture);
        }
    }
}
