using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Path = System.IO.Path;

namespace Volta.Setup
{
    internal static class Ui
    {
        public static int Run(Options opt, Payload payload, bool uninstall)
        {
            var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
            Theme.LoadFonts();
            Theme.LoadStyles();
            var existing = Existing.Detect();
            var window = new SetupWindow(opt, payload, uninstall, existing, screenshot: false);
            window.Begin();
            app.Run(window);
            return window.ExitCode;
        }
    }

    /// <summary>
    /// The installer's window: the disc and the name on the left, the page on the right,
    /// pill buttons at the bottom, all in Volta's dark theme. One window serves the
    /// install, the update and the uninstall.
    /// </summary>
    internal sealed class SetupWindow : Window
    {
        public int ExitCode = 2;

        private readonly Options opt;
        private readonly Payload payload;
        private readonly bool uninstall;
        private readonly Existing existing;
        private readonly bool screenshot;
        private readonly InstallChoice choice;
        private bool working;
        private bool removeData;

        public readonly Grid Root;
        private readonly Disc disc;
        private readonly Grid page;
        private readonly StackPanel buttons;
        private readonly StackPanel steps;
        private readonly TextBlock stepLabel;
        private readonly Button closeButton;

        // Progress page parts.
        private TextBlock progressStatus, progressPercent;
        private Border progressFill, progressTrack;
        private double progressValue;

        private bool IsUpdate => existing.Any && !uninstall;

        public SetupWindow(Options opt, Payload payload, bool uninstall, Existing existing, bool screenshot)
        {
            this.opt = opt;
            this.payload = payload ?? new Payload();
            this.uninstall = uninstall;
            this.existing = existing;
            this.screenshot = screenshot;
            choice = Installer.DefaultChoice(existing, opt);

            Title = uninstall ? Text.T.UninstallWindowTitle : Text.T.WindowTitle;
            Width = 860;
            Height = 580;
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            UseLayoutRounding = true;
            FontFamily = Theme.Body;
            FontSize = 14;
            Foreground = Theme.Brush(Theme.Cream);
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Ideal);
            try
            {
                using (var s = typeof(SetupWindow).Assembly.GetManifestResourceStream("icon.png"))
                    Icon = BitmapFrame.Create(s, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
            }
            catch { }

            // The sheet with its shadow; the shadow lives on its own layer so the animated
            // disc never makes it redraw.
            Root = new Grid { Margin = new Thickness(22) };
            TextElement.SetFontFamily(Root, Theme.Body);
            TextElement.SetForeground(Root, Theme.Brush(Theme.Cream));
            Root.Children.Add(new Border
            {
                CornerRadius = new CornerRadius(22),
                Background = Theme.Brush(Theme.Background),
                Effect = new DropShadowEffect { BlurRadius = 30, ShadowDepth = 8, Direction = 270, Opacity = 0.6, Color = Colors.Black }
            });
            var sheet = new Border
            {
                CornerRadius = new CornerRadius(22),
                Background = Theme.Brush(Theme.Background),
                BorderBrush = Theme.Brush(Theme.Outline),
                BorderThickness = new Thickness(1)
            };
            Root.Children.Add(sheet);
            var columns = new Grid();
            columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(300) });
            columns.ColumnDefinitions.Add(new ColumnDefinition());
            sheet.Child = columns;

            // ---- left: the disc, the name, the version.
            var left = new Grid { ClipToBounds = true };
            left.Clip = new RectangleGeometry(new Rect(0, 0, 340, 534), 21, 21);
            left.Children.Add(new Border
            {
                Background = new LinearGradientBrush(Theme.Surface, Theme.Rgb(0x111518), 90),
            });
            left.Children.Add(new Ellipse
            {
                Width = 420,
                Height = 420,
                Margin = new Thickness(-60, 0, -60, 0),
                VerticalAlignment = VerticalAlignment.Top,
                RenderTransform = new TranslateTransform(0, -20),
                Fill = new RadialGradientBrush(Theme.WithAlpha(Theme.Copper, 0.20), Theme.WithAlpha(Theme.Copper, 0))
            });
            left.Children.Add(new Border
            {
                HorizontalAlignment = HorizontalAlignment.Right,
                Width = 1,
                Background = Theme.Brush(Theme.Outline)
            });
            var leftStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, -10, 0, 0) };
            disc = new Disc { Width = 236, Height = 236, HorizontalAlignment = HorizontalAlignment.Center };
            leftStack.Children.Add(disc);
            leftStack.Children.Add(new TextBlock
            {
                Text = "Volta",
                FontFamily = Theme.TitleSemi,
                FontSize = 34,
                Foreground = Theme.Brush(Theme.Cream),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 6, 0, 0)
            });
            leftStack.Children.Add(new TextBlock
            {
                Text = Text.T.Tagline,
                FontFamily = Theme.Body,
                FontSize = 13.5,
                Foreground = Theme.Brush(Theme.Muted),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 6, 0, 0)
            });
            left.Children.Add(leftStack);
            left.Children.Add(new TextBlock
            {
                Text = string.Format(Text.T.VersionLine, this.payload.Version),
                FontFamily = Theme.Medium,
                FontSize = 12,
                Foreground = Theme.Brush(Theme.Muted, 0.8),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(0, 0, 0, 22)
            });
            columns.Children.Add(left);

            // ---- right: steps and close, the page, the buttons.
            var right = new Grid { Margin = new Thickness(40, 22, 24, 28) };
            Grid.SetColumn(right, 1);
            right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            right.RowDefinitions.Add(new RowDefinition());
            right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            columns.Children.Add(right);

            var top = new DockPanel { LastChildFill = false, Height = 36 };
            closeButton = new Button { Style = Theme.GetStyle("IconButton"), Content = CloseGlyph(), ToolTip = Text.T.Close };
            closeButton.Click += (s, e) => Close();
            DockPanel.SetDock(closeButton, Dock.Right);
            top.Children.Add(closeButton);
            steps = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(steps, Dock.Left);
            top.Children.Add(steps);
            stepLabel = new TextBlock
            {
                FontFamily = Theme.Medium,
                FontSize = 12,
                Foreground = Theme.Brush(Theme.Muted),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(12, 0, 0, 1)
            };
            DockPanel.SetDock(stepLabel, Dock.Left);
            top.Children.Add(stepLabel);
            right.Children.Add(top);

            page = new Grid { Margin = new Thickness(0, 22, 16, 0) };
            Grid.SetRow(page, 1);
            right.Children.Add(page);

            buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 0, 16, 0), Height = 50 };
            Grid.SetRow(buttons, 2);
            right.Children.Add(buttons);

            if (!screenshot) Content = Root;
            Root.MouseLeftButtonDown += (s, e) =>
            {
                if (e.ButtonState == MouseButtonState.Pressed && !(e.OriginalSource is TextBox)) { try { DragMove(); } catch { } }
            };
            Closing += (s, e) => { if (working) e.Cancel = true; };
            KeyDown += (s, e) => { if (e.Key == Key.Escape && !working) Close(); };
        }

        /// <summary>The first page, by what this run is for.</summary>
        public void Begin()
        {
            if (uninstall)
            {
                if (opt.Passive) StartUninstall();
                else ShowUninstallConfirm();
            }
            else if (opt.Passive) StartInstall();
            else ShowWelcome();
        }

        // ================================================================ pages

        public void ShowWelcome()
        {
            SetStep(0);
            Idle();
            var p = new StackPanel();
            if (IsUpdate)
            {
                p.Children.Add(H1(Text.T.WelcomeUpdateTitle));
                var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 18, 0, 0) };
                if (!string.IsNullOrEmpty(existing.Version))
                {
                    row.Children.Add(VersionPill(existing.Version, false));
                    row.Children.Add(new TextBlock { Text = "→", FontFamily = Theme.Medium, FontSize = 18, Foreground = Theme.Brush(Theme.Muted), Margin = new Thickness(12, 0, 12, 2), VerticalAlignment = VerticalAlignment.Center });
                }
                row.Children.Add(VersionPill(payload.Version, true));
                p.Children.Add(row);
                bool same = existing.Version == payload.Version;
                p.Children.Add(Body(same ? string.Format(Text.T.Reinstall, payload.Version) : string.Format(Text.T.UpdateFromTo, existing.Version, payload.Version), 15, Theme.Cream, new Thickness(0, 18, 0, 0), Theme.Semi));
                p.Children.Add(Card(IconRow(CheckIcon(), null, string.Format(Text.T.UpdateKeeps, existing.Version ?? "—")), new Thickness(0, 14, 0, 0)));
                p.Children.Add(Chips(new Thickness(0, 16, 0, 0)));
                SetPage(p);
                SetButtons(Ghost(Text.T.Cancel, Close, cancel: true), Primary(Text.T.Update, StartInstall));
            }
            else
            {
                p.Children.Add(H1(Text.T.WelcomeTitle));
                p.Children.Add(Body(Text.T.WelcomeBody, 14.5, Theme.CreamSoft, new Thickness(0, 12, 0, 0)));
                p.Children.Add(Card(IconRow(InfoIcon(), Text.T.NoServersTitle, Text.T.NoServersBody), new Thickness(0, 20, 0, 0)));
                p.Children.Add(Chips(new Thickness(0, 16, 0, 0)));
                SetPage(p);
                SetButtons(Ghost(Text.T.Cancel, Close, cancel: true), Primary(Text.T.Next, ShowPlace));
            }
        }

        public void ShowPlace()
        {
            SetStep(1);
            Idle();
            var p = new StackPanel();
            p.Children.Add(H1(Text.T.PlaceTitle));
            p.Children.Add(Body(Text.T.PlaceBody, 14.5, Theme.CreamSoft, new Thickness(0, 12, 0, 0)));

            var row = new Grid { Margin = new Thickness(0, 22, 0, 0) };
            row.ColumnDefinitions.Add(new ColumnDefinition());
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var path = new TextBox { Style = Theme.GetStyle("Field"), Text = choice.Dir, FontFamily = Theme.Body };
            row.Children.Add(path);
            var browse = Ghost(Text.T.Browse, () =>
            {
                var picked = Native.PickFolder(new WindowInteropHelper(this).Handle, Text.T.PlaceFolderPicker, path.Text);
                if (picked != null) path.Text = Installer.Normalize(picked, existing);
            });
            browse.MinWidth = 0;
            browse.Padding = new Thickness(20, 0, 20, 0);
            browse.Margin = new Thickness(10, 0, 0, 0);
            Grid.SetColumn(browse, 1);
            row.Children.Add(browse);
            p.Children.Add(row);

            var space = Body("", 12.5, Theme.Muted, new Thickness(4, 9, 0, 0));
            void UpdateSpace()
            {
                string free = "—";
                try
                {
                    var root = Path.GetPathRoot(Path.GetFullPath(path.Text));
                    free = Text.Size(new DriveInfo(root).AvailableFreeSpace);
                }
                catch { }
                space.Text = string.Format(Text.T.PlaceSpaceFormat, Text.Size(Math.Max(payload.Size, 1)), free);
            }
            path.TextChanged += (s, e) => UpdateSpace();
            UpdateSpace();
            p.Children.Add(space);

            var options = new StackPanel();
            var desktop = Switch(Text.T.OptDesktop, null, choice.DesktopShortcut);
            var start = Switch(Text.T.OptStartMenu, null, choice.StartMenu);
            var auto = Switch(Text.T.OptAutostart, Text.T.OptAutostartHint, choice.Autostart);
            options.Children.Add(desktop);
            options.Children.Add(Divider());
            options.Children.Add(start);
            options.Children.Add(Divider());
            options.Children.Add(auto);
            p.Children.Add(Card(options, new Thickness(0, 18, 0, 0), new Thickness(18, 6, 16, 6)));
            SetPage(p);

            SetButtons(Ghost(Text.T.Back, ShowWelcome), Primary(Text.T.Install, () =>
            {
                try
                {
                    var dir = Installer.Normalize(path.Text, existing);
                    if (dir.Length < 4 || !Path.IsPathRooted(dir)) throw new IOException();
                    choice.Dir = dir;
                }
                catch
                {
                    space.Text = Text.T.ErrorFolder;
                    space.Foreground = Theme.Brush(Theme.Error);
                    return;
                }
                choice.DesktopShortcut = desktop.IsChecked == true;
                choice.StartMenu = start.IsChecked == true;
                choice.Autostart = auto.IsChecked == true;
                StartInstall();
            }));
        }

        public void ShowProgress(string title, double value, string status)
        {
            SetStep(uninstall ? 1 : 2);
            if (!screenshot) disc.Animate(working: true);
            else { disc.Phase = 0.3; disc.Spark = 230; disc.SparkAlpha = 1; }
            var p = new StackPanel();
            p.Children.Add(H1(title));
            progressStatus = Body(status, 14.5, Theme.CreamSoft, new Thickness(0, 12, 0, 0));
            p.Children.Add(progressStatus);
            progressPercent = new TextBlock
            {
                FontFamily = Theme.TitleSemi,
                FontSize = 46,
                Foreground = Theme.Brush(Theme.Copper),
                Margin = new Thickness(0, 26, 0, 10)
            };
            p.Children.Add(progressPercent);
            progressTrack = new Border { Height = 10, CornerRadius = new CornerRadius(5), Background = Theme.Brush(Theme.SurfaceHigh) };
            progressFill = new Border
            {
                Height = 10,
                CornerRadius = new CornerRadius(5),
                HorizontalAlignment = HorizontalAlignment.Left,
                Width = 0,
                Background = new LinearGradientBrush(Theme.Metals.Copper, Theme.Metals.CopperLight, 0)
            };
            var bar = new Grid();
            bar.Children.Add(progressTrack);
            bar.Children.Add(progressFill);
            bar.SizeChanged += (s, e) => SetProgress(progressValue, null);
            p.Children.Add(bar);
            if (!uninstall || !removeData)
                p.Children.Add(Card(IconRow(CheckIcon(), null, Text.T.ProgressNote), new Thickness(0, 28, 0, 0)));
            SetPage(p);
            SetButtons();
            SetProgress(value, status);
        }

        private void SetProgress(double value, string status)
        {
            progressValue = Math.Max(progressValue, Math.Max(0, Math.Min(1, value)));
            if (progressPercent != null) progressPercent.Text = ((int)Math.Round(progressValue * 100)) + "%";
            if (status != null && progressStatus != null) progressStatus.Text = status;
            if (progressFill != null && progressTrack != null)
            {
                double w = progressTrack.ActualWidth > 0 ? progressTrack.ActualWidth : progressTrack.Width;
                if (double.IsNaN(w) || w <= 0) w = 440;
                progressFill.Width = Math.Max(10, w * progressValue);
            }
        }

        public void ShowDone()
        {
            SetStep(3);
            disc.Stop();
            disc.ToPatina(animate: !screenshot);
            if (!screenshot) disc.Animate(working: false);
            var p = new StackPanel();
            p.Children.Add(H1(Text.T.DoneTitle));
            p.Children.Add(Body(IsUpdate ? string.Format(Text.T.DoneUpdatedFormat, payload.Version) : Text.T.DoneBody, 14.5, Theme.CreamSoft, new Thickness(0, 12, 0, 0)));
            p.Children.Add(Card(IconRow(CheckIcon(), null, Text.T.DoneNote), new Thickness(0, 22, 0, 0)));
            SetPage(p);
            var launch = Primary(Text.T.Launch, () =>
            {
                Installer.LaunchVolta(choice.Dir);
                ExitCode = 0;
                Close();
            });
            SetButtons(Ghost(Text.T.Close, () => { ExitCode = 0; Close(); }, cancel: true), launch);
            ExitCode = 0;
            if (!screenshot) launch.Focus();
        }

        public void ShowError(string message)
        {
            disc.Stop();
            disc.Dim = 0.6;
            var p = new StackPanel();
            p.Children.Add(H1(Text.T.ErrorTitle));
            p.Children.Add(Body(uninstall ? Text.T.ErrorUninstall : Text.T.ErrorInstall, 14.5, Theme.CreamSoft, new Thickness(0, 12, 0, 0)));
            var details = new StackPanel();
            details.Children.Add(Body(message, 13, Theme.Cream, new Thickness(0)));
            details.Children.Add(Body(string.Format(Text.T.LogLine, Log.Path), 12, Theme.Muted, new Thickness(0, 10, 0, 0)));
            p.Children.Add(Card(details, new Thickness(0, 20, 0, 0), accent: Theme.Error));
            SetPage(p);
            SetButtons(Ghost(Text.T.OpenLog, () => { try { Process.Start(new ProcessStartInfo("notepad.exe", "\"" + Log.Path + "\"")); } catch { } }),
                Primary(Text.T.Close, () => { ExitCode = 1; Close(); }));
            ExitCode = 1;
        }

        public void ShowUninstallConfirm()
        {
            SetStep(0);
            Idle();
            var p = new StackPanel();
            p.Children.Add(H1(Text.T.UninstallTitle));
            p.Children.Add(Body(Text.T.UninstallBody, 14.5, Theme.CreamSoft, new Thickness(0, 12, 0, 0)));
            var data = Switch(Text.T.RemoveDataQuestion, Text.T.RemoveDataHint, removeData);
            p.Children.Add(Card(data, new Thickness(0, 22, 0, 0), new Thickness(18, 14, 16, 14)));
            SetPage(p);
            var remove = Primary(Text.T.Remove, () =>
            {
                removeData = data.IsChecked == true;
                StartUninstall();
            });
            void Tint() => remove.Style = Theme.GetStyle(data.IsChecked == true ? "Danger" : "Pill");
            data.Checked += (s, e) => Tint();
            data.Unchecked += (s, e) => Tint();
            Tint();
            var cancel = Ghost(Text.T.Cancel, Close, cancel: true);
            cancel.IsDefault = true;
            remove.IsDefault = false;
            SetButtons(cancel, remove);
        }

        /// <summary>For the screenshots: the confirm page with the data switch on.</summary>
        public void ShowUninstallConfirmWithData()
        {
            removeData = true;
            ShowUninstallConfirm();
        }

        public void ShowUninstallDone()
        {
            SetStep(2);
            disc.Stop();
            disc.Dim = 0.35;
            var p = new StackPanel();
            p.Children.Add(H1(Text.T.UninstallDoneTitle));
            p.Children.Add(Body(removeData ? Text.T.UninstallDoneRemoved : Text.T.UninstallDoneKept, 14.5, Theme.CreamSoft, new Thickness(0, 12, 0, 0)));
            SetPage(p);
            SetButtons(Primary(Text.T.Close, () => { ExitCode = 0; Close(); }));
            ExitCode = 0;
        }

        // ================================================================ work

        private void StartInstall()
        {
            working = true;
            closeButton.IsEnabled = false;
            ShowProgress(IsUpdate ? Text.T.ProgressUpdate : Text.T.ProgressInstall, 0, Text.T.StatusPreparing);
            Task.Run(() =>
            {
                try
                {
                    Installer.Install(payload, existing, choice, OnProgress);
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        working = false;
                        closeButton.IsEnabled = true;
                        if (opt.Passive)
                        {
                            ExitCode = 0;
                            if (opt.Launch) Installer.LaunchVolta(choice.Dir);
                            Close();
                        }
                        else ShowDone();
                    }));
                }
                catch (Exception e)
                {
                    Log.Line("install failed: " + e);
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        working = false;
                        closeButton.IsEnabled = true;
                        ShowError(e.Message);
                    }));
                }
            });
        }

        private void StartUninstall()
        {
            working = true;
            closeButton.IsEnabled = false;
            ShowProgress(Text.T.ProgressUninstall, 0, Text.T.StatusClosing);
            Task.Run(() =>
            {
                try
                {
                    Uninstaller.Uninstall(removeData, opt.Dir, OnProgress);
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        working = false;
                        closeButton.IsEnabled = true;
                        if (opt.Passive) { ExitCode = 0; Close(); }
                        else ShowUninstallDone();
                    }));
                }
                catch (Exception e)
                {
                    Log.Line("uninstall failed: " + e);
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        working = false;
                        closeButton.IsEnabled = true;
                        ShowError(e.Message);
                    }));
                }
            });
        }

        private void OnProgress(double fraction, string status) =>
            Dispatcher.BeginInvoke(new Action(() => SetProgress(fraction, status)), DispatcherPriority.Background);

        // ================================================================ building blocks

        private void Idle()
        {
            if (screenshot) { disc.Phase = 0.42; return; }
            disc.Animate(working: false);
        }

        private void SetStep(int index)
        {
            var names = uninstall
                ? new[] { Text.T.StepConfirm, Text.T.StepRemoving, Text.T.StepDone }
                : new[] { Text.T.StepWelcome, Text.T.StepPlace, Text.T.StepInstall, Text.T.StepDone };
            if (IsUpdate && index >= 2) index -= 1;
            if (IsUpdate) names = new[] { Text.T.StepUpdate, Text.T.StepInstall, Text.T.StepDone };
            index = Math.Min(index, names.Length - 1);
            steps.Children.Clear();
            for (int i = 0; i < names.Length; i++)
            {
                steps.Children.Add(new Border
                {
                    Width = i == index ? 26 : 7,
                    Height = 7,
                    CornerRadius = new CornerRadius(3.5),
                    Margin = new Thickness(0, 0, 6, 0),
                    Background = Theme.Brush(i == index ? Theme.Copper : i < index ? Theme.CopperDeep : Theme.Outline)
                });
            }
            stepLabel.Text = (index + 1) + " / " + names.Length + "  ·  " + names[index];
        }

        private void SetPage(UIElement content)
        {
            page.Children.Clear();
            page.Children.Add(content);
            if (screenshot) return;
            var shift = new TranslateTransform(18, 0);
            content.RenderTransform = shift;
            content.Opacity = 0;
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            content.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)) { EasingFunction = ease });
            shift.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(18, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
        }

        private void SetButtons(params Button[] list)
        {
            buttons.Children.Clear();
            for (int i = 0; i < list.Length; i++)
            {
                if (i > 0) list[i].Margin = new Thickness(12, 0, 0, 0);
                buttons.Children.Add(list[i]);
            }
        }

        private Button Primary(string text, Action click)
        {
            var b = new Button { Style = Theme.GetStyle("Pill"), Content = text, FontFamily = Theme.Semi, IsDefault = true, VerticalAlignment = VerticalAlignment.Center };
            b.Click += (s, e) => click();
            return b;
        }

        private Button Ghost(string text, Action click, bool cancel = false)
        {
            var b = new Button { Style = Theme.GetStyle("Ghost"), Content = text, FontFamily = Theme.Semi, IsCancel = cancel, VerticalAlignment = VerticalAlignment.Center };
            b.Click += (s, e) => click();
            return b;
        }

        private static TextBlock H1(string text) => new TextBlock
        {
            Text = text,
            FontFamily = Theme.Title,
            FontSize = 25,
            LineHeight = 34,
            TextWrapping = TextWrapping.Wrap,
            Foreground = Theme.Brush(Theme.Cream)
        };

        private static TextBlock Body(string text, double size, Color color, Thickness margin, FontFamily font = null) => new TextBlock
        {
            Text = text,
            FontFamily = font ?? Theme.Body,
            FontSize = size,
            LineHeight = Math.Round(size * 1.5),
            TextWrapping = TextWrapping.Wrap,
            Foreground = Theme.Brush(color),
            Margin = margin
        };

        private static Border Card(UIElement child, Thickness margin, Thickness? padding = null, Color? accent = null) => new Border
        {
            Background = Theme.Brush(Theme.Surface),
            BorderBrush = Theme.Brush(accent.HasValue ? Theme.WithAlpha(accent.Value, 0.55) : Theme.Outline),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Padding = padding ?? new Thickness(18, 16, 18, 16),
            Margin = margin,
            Child = child
        };

        private static UIElement IconRow(UIElement icon, string title, string body)
        {
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            if (icon is FrameworkElement fe) fe.VerticalAlignment = VerticalAlignment.Top;
            g.Children.Add(icon);
            var text = new StackPanel { Margin = new Thickness(14, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            if (title != null) text.Children.Add(Body(title, 14.5, Theme.Cream, new Thickness(0, 0, 0, 4), Theme.Semi));
            text.Children.Add(Body(body, 13, title != null ? Theme.Muted : Theme.CreamSoft, new Thickness(0, title != null ? 0 : 6, 0, 0)));
            Grid.SetColumn(text, 1);
            g.Children.Add(text);
            return g;
        }

        private static UIElement Badge(Color tint, UIElement glyph)
        {
            var g = new Grid { Width = 36, Height = 36 };
            g.Children.Add(new Ellipse { Fill = Theme.Brush(tint, 0.16), Stroke = Theme.Brush(tint, 0.35), StrokeThickness = 1 });
            g.Children.Add(glyph);
            return g;
        }

        private static UIElement InfoIcon() => Badge(Theme.Copper, new System.Windows.Shapes.Path
        {
            Data = Geometry.Parse("M 8,3.2 A 1.3,1.3 0 1 1 8,5.8 A 1.3,1.3 0 1 1 8,3.2 Z M 6.9,7.6 L 9.1,7.6 L 9.1,13.4 L 6.9,13.4 Z"),
            Fill = Theme.Brush(Theme.CopperSoft),
            Width = 16,
            Height = 16,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        });

        private static UIElement CheckIcon() => Badge(Theme.Success, new System.Windows.Shapes.Path
        {
            Data = Geometry.Parse("M 2.5,8.5 L 6.5,12.2 L 13.5,4.2"),
            Stroke = Theme.Brush(Theme.Metals.PatinaLight),
            StrokeThickness = 2,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
            StrokeLineJoin = PenLineJoin.Round,
            Width = 16,
            Height = 16,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        });

        private static UIElement CloseGlyph() => new System.Windows.Shapes.Path
        {
            Data = Geometry.Parse("M 1,1 L 11,11 M 11,1 L 1,11"),
            Stroke = new SolidColorBrush(Theme.Muted),
            StrokeThickness = 1.6,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
            Width = 12,
            Height = 12
        };

        private UIElement Chips(Thickness margin)
        {
            var w = new WrapPanel { Margin = margin };
            foreach (var t in new[] { Text.T.FactNoAdmin, Text.T.FactWindows, string.Format(Text.T.FactSizeFormat, Text.Size(Math.Max(1, payload.Size))) })
            {
                var row = new StackPanel { Orientation = Orientation.Horizontal };
                row.Children.Add(new Ellipse { Width = 6, Height = 6, Fill = Theme.Brush(Theme.Copper), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 1, 8, 0) });
                row.Children.Add(new TextBlock { Text = t, FontFamily = Theme.Medium, FontSize = 12.5, Foreground = Theme.Brush(Theme.CreamSoft), VerticalAlignment = VerticalAlignment.Center });
                w.Children.Add(new Border
                {
                    Background = Theme.Brush(Theme.SurfaceHigh),
                    CornerRadius = new CornerRadius(15),
                    Padding = new Thickness(12, 6, 14, 7),
                    Margin = new Thickness(0, 0, 8, 8),
                    Child = row
                });
            }
            return w;
        }

        private static UIElement VersionPill(string version, bool current) => new Border
        {
            Background = Theme.Brush(current ? Theme.Copper : Theme.SurfaceHigh, current ? 0.16 : 1),
            BorderBrush = Theme.Brush(current ? Theme.Copper : Theme.Outline, current ? 0.6 : 1),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(16),
            Padding = new Thickness(14, 6, 14, 7),
            Child = new TextBlock
            {
                Text = version,
                FontFamily = Theme.Title,
                FontSize = 16,
                Foreground = Theme.Brush(current ? Theme.CopperSoft : Theme.Muted)
            }
        };

        private static CheckBox Switch(string title, string hint, bool on)
        {
            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock { Text = title, FontFamily = Theme.Medium, FontSize = 14.5, Foreground = Theme.Brush(Theme.Cream), TextWrapping = TextWrapping.Wrap });
            if (hint != null)
                text.Children.Add(new TextBlock { Text = hint, FontFamily = Theme.Body, FontSize = 12.5, LineHeight = 19, Foreground = Theme.Brush(Theme.Muted), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 3, 0, 0) });
            return new CheckBox { Style = Theme.GetStyle("Switch"), Content = text, IsChecked = on, Padding = new Thickness(0), Margin = new Thickness(0, 9, 0, 9) };
        }

        private static UIElement Divider() => new Border { Height = 1, Background = Theme.Brush(Theme.Outline, 0.7) };
    }
}
