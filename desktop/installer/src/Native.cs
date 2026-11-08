using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;

namespace Volta.Setup
{
    /// <summary>Everything the installer asks of Windows directly.</summary>
    internal static class Native
    {
        // ------------------------------------------------------------ message box

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int MessageBoxW(IntPtr hwnd, string text, string caption, uint type);

        public static void Message(string title, string text, bool error)
        {
            try { MessageBoxW(IntPtr.Zero, text, title, (error ? 0x10u : 0x40u) | 0x10000u); } catch { }
        }

        // ------------------------------------------------------------ processes

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint access, bool inherit, int pid);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool QueryFullProcessImageNameW(IntPtr process, int flags, StringBuilder name, ref int size);

        private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

        /// <summary>The full path of a process's exe (works across 32/64 bit and without admin rights for the user's own processes).</summary>
        public static string ProcessPath(int pid)
        {
            IntPtr h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (h == IntPtr.Zero) return null;
            try
            {
                var sb = new StringBuilder(1024);
                int size = sb.Capacity;
                return QueryFullProcessImageNameW(h, 0, sb, ref size) ? sb.ToString(0, size) : null;
            }
            finally { CloseHandle(h); }
        }

        /// <summary>Running processes whose exe lies inside one of [dirs].</summary>
        public static List<Process> ProcessesIn(IEnumerable<string> dirs)
        {
            var roots = new List<string>();
            foreach (var d in dirs)
            {
                if (string.IsNullOrWhiteSpace(d)) continue;
                try { roots.Add(Path.GetFullPath(d).TrimEnd('\\') + "\\"); } catch { }
            }
            var found = new List<Process>();
            int me = Process.GetCurrentProcess().Id;
            foreach (var p in Process.GetProcesses())
            {
                if (p.Id == me || p.Id <= 4) continue;
                string path = null;
                try { path = ProcessPath(p.Id); } catch { }
                if (path == null) continue;
                foreach (var r in roots)
                {
                    if (path.StartsWith(r, StringComparison.OrdinalIgnoreCase)) { found.Add(p); break; }
                }
            }
            return found;
        }

        [DllImport("user32.dll")]
        private static extern bool AllowSetForegroundWindow(int pid);

        public static void AllowForeground() { try { AllowSetForegroundWindow(-1); } catch { } }

        // ------------------------------------------------------------ shortcuts

        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
        private class ShellLink { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
        private interface IShellLinkW
        {
            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cchMaxPath, IntPtr pfd, int fFlags);
            void GetIDList(out IntPtr ppidl);
            void SetIDList(IntPtr pidl);
            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cchMaxName);
            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cchMaxPath);
            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cchMaxPath);
            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
            void GetHotkey(out short pwHotkey);
            void SetHotkey(short wHotkey);
            void GetShowCmd(out int piShowCmd);
            void SetShowCmd(int iShowCmd);
            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cchIconPath, out int piIcon);
            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, int dwReserved);
            void Resolve(IntPtr hwnd, int fFlags);
            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
        }

        public static void CreateShortcut(string lnk, string target, string workDir, string description, string args = null)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(lnk));
            var link = (IShellLinkW)new ShellLink();
            try
            {
                link.SetPath(target);
                link.SetWorkingDirectory(workDir);
                link.SetDescription(description);
                link.SetIconLocation(target, 0);
                if (!string.IsNullOrEmpty(args)) link.SetArguments(args);
                ((IPersistFile)link).Save(lnk, true);
            }
            finally { Marshal.ReleaseComObject(link); }
        }

        /// <summary>A shortcut's target, or null when it can't be read.</summary>
        public static string ShortcutTarget(string lnk)
        {
            if (!File.Exists(lnk)) return null;
            var link = (IShellLinkW)new ShellLink();
            try
            {
                ((IPersistFile)link).Load(lnk, 0);
                var sb = new StringBuilder(1024);
                link.GetPath(sb, sb.Capacity, IntPtr.Zero, 0x4 /* SLGP_RAWPATH */);
                return sb.ToString();
            }
            catch { return null; }
            finally { Marshal.ReleaseComObject(link); }
        }

        [DllImport("shell32.dll")]
        private static extern void SHChangeNotify(int eventId, uint flags, IntPtr item1, IntPtr item2);

        /// <summary>Explorer redraws icons and the Start menu.</summary>
        public static void ShellChanged() { try { SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero); } catch { } }

        // ------------------------------------------------------------ folder picker

        [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
        private class FileOpenDialogRcw { }

        [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IFileOpenDialog
        {
            [PreserveSig] int Show(IntPtr parent);
            void SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
            void SetFileTypeIndex(uint iFileType);
            void GetFileTypeIndex(out uint piFileType);
            void Advise(IntPtr pfde, out uint pdwCookie);
            void Unadvise(uint dwCookie);
            void SetOptions(uint fos);
            void GetOptions(out uint pfos);
            void SetDefaultFolder(IShellItem psi);
            void SetFolder(IShellItem psi);
            void GetFolder(out IShellItem ppsi);
            void GetCurrentSelection(out IShellItem ppsi);
            void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
            void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
            void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
            void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
            void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
            void GetResult(out IShellItem ppsi);
            void AddPlace(IShellItem psi, int fdap);
            void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
            void Close(int hr);
            void SetClientGuid(ref Guid guid);
            void ClearClientData();
            void SetFilter(IntPtr pFilter);
            void GetResults(out IntPtr ppenum);
            void GetSelectedItems(out IntPtr ppsai);
        }

        [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IShellItem
        {
            void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
            void GetParent(out IShellItem ppsi);
            void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
            void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
            void Compare(IShellItem psi, uint hint, out int piOrder);
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        private static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, ref Guid riid, out IShellItem item);

        /// <summary>Windows' own folder picker; null when cancelled.</summary>
        public static string PickFolder(IntPtr owner, string title, string start)
        {
            var dialog = (IFileOpenDialog)new FileOpenDialogRcw();
            try
            {
                dialog.SetOptions(0x20 /* PICKFOLDERS */ | 0x40 /* FORCEFILESYSTEM */ | 0x800 /* PATHMUSTEXIST */);
                dialog.SetTitle(title);
                try
                {
                    string existing = start;
                    while (!string.IsNullOrEmpty(existing) && !Directory.Exists(existing)) existing = Path.GetDirectoryName(existing);
                    if (!string.IsNullOrEmpty(existing))
                    {
                        var iid = typeof(IShellItem).GUID;
                        SHCreateItemFromParsingName(existing, IntPtr.Zero, ref iid, out var folder);
                        dialog.SetFolder(folder);
                    }
                }
                catch { }
                if (dialog.Show(owner) != 0) return null;
                dialog.GetResult(out var item);
                item.GetDisplayName(0x80058000 /* SIGDN_FILESYSPATH */, out var path);
                return path;
            }
            finally { Marshal.ReleaseComObject(dialog); }
        }

        // ------------------------------------------------------------ the old MSI installs

        [DllImport("msi.dll", CharSet = CharSet.Unicode)]
        private static extern int MsiEnumRelatedProductsW(string upgradeCode, int reserved, int index, StringBuilder productCode);

        [DllImport("msi.dll", CharSet = CharSet.Unicode)]
        private static extern int MsiGetProductInfoW(string product, string property, StringBuilder value, ref int size);

        public static List<string> RelatedProducts(string upgradeCode)
        {
            var list = new List<string>();
            try
            {
                for (int i = 0; ; i++)
                {
                    var sb = new StringBuilder(39);
                    if (MsiEnumRelatedProductsW(upgradeCode, 0, i, sb) != 0) break;
                    list.Add(sb.ToString());
                }
            }
            catch { }
            return list;
        }

        public static string ProductInfo(string product, string property)
        {
            try
            {
                int size = 1024;
                var sb = new StringBuilder(size);
                return MsiGetProductInfoW(product, property, sb, ref size) == 0 ? sb.ToString() : null;
            }
            catch { return null; }
        }

        // ------------------------------------------------------------ WinINet

        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool InternetSetOptionW(IntPtr hInternet, int option, IntPtr buffer, int length);

        private const int INTERNET_OPTION_REFRESH = 37;
        private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
        private const int INTERNET_OPTION_PER_CONNECTION_OPTION = 75;
        private const int INTERNET_OPTION_PROXY_SETTINGS_CHANGED = 95;

        /// <summary>The LAN connection's proxy options, as Volta's own SystemProxy writes them.</summary>
        public static bool SetPerConnection(int flags, string server, string bypass, string autoConfigUrl)
        {
            int ptr = IntPtr.Size;
            int optionSize = ptr == 8 ? 16 : 12;
            int valueAt = ptr == 8 ? 8 : 4;
            int listSize = ptr == 8 ? 32 : 20;
            var texts = new[] { null, server ?? "", bypass ?? "", autoConfigUrl ?? "" };
            int[] ids = { 1, 2, 3, 4 };
            var allocated = new List<IntPtr>();
            IntPtr options = Marshal.AllocHGlobal(optionSize * ids.Length);
            IntPtr list = Marshal.AllocHGlobal(listSize);
            try
            {
                for (int i = 0; i < optionSize * ids.Length; i++) Marshal.WriteByte(options, i, 0);
                for (int i = 0; i < ids.Length; i++)
                {
                    IntPtr at = options + i * optionSize;
                    Marshal.WriteInt32(at, ids[i]);
                    if (texts[i] == null) Marshal.WriteInt32(at + valueAt, flags);
                    else
                    {
                        IntPtr s = Marshal.StringToHGlobalUni(texts[i]);
                        allocated.Add(s);
                        Marshal.WriteIntPtr(at + valueAt, s);
                    }
                }
                for (int i = 0; i < listSize; i++) Marshal.WriteByte(list, i, 0);
                Marshal.WriteInt32(list, listSize);
                Marshal.WriteIntPtr(list + (ptr == 8 ? 8 : 4), IntPtr.Zero);
                Marshal.WriteInt32(list + (ptr == 8 ? 16 : 8), ids.Length);
                Marshal.WriteInt32(list + (ptr == 8 ? 20 : 12), 0);
                Marshal.WriteIntPtr(list + (ptr == 8 ? 24 : 16), options);
                return InternetSetOptionW(IntPtr.Zero, INTERNET_OPTION_PER_CONNECTION_OPTION, list, listSize);
            }
            catch { return false; }
            finally
            {
                foreach (var a in allocated) Marshal.FreeHGlobal(a);
                Marshal.FreeHGlobal(options);
                Marshal.FreeHGlobal(list);
            }
        }

        public static void ProxyChanged()
        {
            try
            {
                InternetSetOptionW(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
                InternetSetOptionW(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
                InternetSetOptionW(IntPtr.Zero, INTERNET_OPTION_PROXY_SETTINGS_CHANGED, IntPtr.Zero, 0);
            }
            catch { }
        }

        // ------------------------------------------------------------ files

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool MoveFileExW(string from, string to, int flags);

        /// <summary>A rename on the same drive (which works even for a running exe); false otherwise.</summary>
        public static bool MoveFile(string from, string to) => MoveFileExW(from, to, 0x1 /* REPLACE_EXISTING */);
    }
}
