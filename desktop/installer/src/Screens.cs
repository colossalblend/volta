using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Volta.Setup
{
    /// <summary>
    /// `--screenshots DIR`: every page of the installer and the uninstaller, in Russian and
    /// English, as PNGs (twice the pixels, on a plain desktop-like backdrop). CI attaches them
    /// to the build so the design can be looked at without installing anything.
    /// </summary>
    internal static class Screens
    {
        public static int Render(string dir)
        {
            Directory.CreateDirectory(dir);
            Theme.LoadFonts();
            Theme.LoadStyles();
            var payload = new Payload { Version = "1.2.0", VersionCode = 300, Size = 196_000_000 };
            int count = 0;
            foreach (var lang in new[] { "ru", "en" })
            {
                Text.Choose(lang);
                var fresh = new Existing();
                var update = new Existing { Ours = true, Dir = Places.DefaultInstallDir, Version = "1.1.140" };
                var opt = new Options();

                Shot(dir, lang, "01-welcome", new SetupWindow(opt, payload, false, fresh, true), w => w.ShowWelcome()); count++;
                Shot(dir, lang, "02-location", new SetupWindow(opt, payload, false, fresh, true), w => w.ShowPlace()); count++;
                Shot(dir, lang, "03-progress", new SetupWindow(opt, payload, false, fresh, true), w => w.ShowProgress(Text.T.ProgressInstall, 0.62, Text.T.StatusCopying)); count++;
                Shot(dir, lang, "04-done", new SetupWindow(opt, payload, false, fresh, true), w => w.ShowDone()); count++;
                Shot(dir, lang, "05-update", new SetupWindow(opt, payload, false, update, true), w => w.ShowWelcome()); count++;
                Shot(dir, lang, "06-update-progress", new SetupWindow(opt, payload, false, update, true), w => w.ShowProgress(Text.T.ProgressUpdate, 0.35, Text.T.StatusClosing)); count++;
                Shot(dir, lang, "07-update-done", new SetupWindow(opt, payload, false, update, true), w => w.ShowDone()); count++;
                Shot(dir, lang, "08-error", new SetupWindow(opt, payload, false, fresh, true), w => w.ShowError(Text.T.ErrorLocked + " C:\\Users\\Арсений\\AppData\\Local\\Programs\\Volta\\runtime\\bin\\server\\jvm.dll")); count++;
                Shot(dir, lang, "09-uninstall", new SetupWindow(opt, payload, true, update, true), w => w.ShowUninstallConfirm()); count++;
                Shot(dir, lang, "10-uninstall-remove-data", new SetupWindow(opt, payload, true, update, true), w => w.ShowUninstallConfirmWithData()); count++;
                Shot(dir, lang, "11-uninstall-progress", new SetupWindow(opt, payload, true, update, true), w => w.ShowProgress(Text.T.ProgressUninstall, 0.55, Text.T.StatusRemovingFiles)); count++;
                Shot(dir, lang, "12-uninstall-done", new SetupWindow(opt, payload, true, update, true), w => w.ShowUninstallDone()); count++;
            }
            Log.Line("screenshots: " + count + " in " + dir);
            Console.WriteLine("screenshots: " + count);
            return 0;
        }

        private static void Shot(string dir, string lang, string name, SetupWindow w, Action<SetupWindow> page)
        {
            page(w);
            double width = w.Width, height = w.Height;
            var host = new Grid
            {
                Width = width,
                Height = height,
                Background = new LinearGradientBrush(Theme.Rgb(0x2E3A44), Theme.Rgb(0x1B2329), 60)
            };
            host.Children.Add(w.Root);
            host.Measure(new Size(width, height));
            host.Arrange(new Rect(0, 0, width, height));
            host.UpdateLayout();
            // Once more: the progress bar sizes itself from the track's width.
            host.Measure(new Size(width, height));
            host.Arrange(new Rect(0, 0, width, height));
            host.UpdateLayout();
            const double scale = 2;
            var bmp = new RenderTargetBitmap((int)(width * scale), (int)(height * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            bmp.Render(host);
            var png = new PngBitmapEncoder();
            png.Frames.Add(BitmapFrame.Create(bmp));
            using (var f = File.Create(Path.Combine(dir, "installer-" + lang + "-" + name + ".png"))) png.Save(f);
            host.Children.Clear();
        }
    }
}
