using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;

namespace Volta.Setup
{
    /// <summary>
    /// Volta's dark look (the Android app's ui/theme/Color.kt): near-black blue background,
    /// tonal zinc surfaces, copper for action, patina green for «all good»; Onest for text
    /// and Unbounded for titles, as in the app.
    /// </summary>
    internal static class Theme
    {
        public static readonly Color Background = Rgb(0x101316);
        public static readonly Color Surface = Rgb(0x181D21);
        public static readonly Color SurfaceHigh = Rgb(0x22282D);
        public static readonly Color Outline = Rgb(0x2D343A);
        public static readonly Color Cream = Rgb(0xEEE8DF);
        public static readonly Color CreamSoft = Rgb(0xB9B4AC);
        public static readonly Color Muted = Rgb(0x8E979E);
        public static readonly Color Copper = Rgb(0xD08350);
        public static readonly Color CopperSoft = Rgb(0xF1B88E);
        public static readonly Color CopperDeep = Rgb(0x7A3F1C);
        public static readonly Color CopperInk = Rgb(0x2B1306);
        public static readonly Color Success = Rgb(0x63C2A6);
        public static readonly Color Error = Rgb(0xE46F5C);

        public static class Metals
        {
            public static readonly Color CopperLight = Rgb(0xF0B486);
            public static readonly Color Copper = Rgb(0xC9773F);
            public static readonly Color CopperDark = Rgb(0x7B3F1B);
            public static readonly Color CopperInk = Rgb(0x3E1A08);
            public static readonly Color PatinaLight = Rgb(0xA6E3CF);
            public static readonly Color Patina = Rgb(0x52B094);
            public static readonly Color PatinaDark = Rgb(0x1E5E4E);
            public static readonly Color PatinaInk = Rgb(0x0B2F27);
            public static readonly Color Spark = Rgb(0xFFE6C7);
        }

        public static SolidColorBrush Brush(Color c, double alpha = 1)
        {
            var b = new SolidColorBrush(Color.FromArgb((byte)Math.Round(255 * alpha), c.R, c.G, c.B));
            b.Freeze();
            return b;
        }

        public static Color Rgb(int rgb) => Color.FromRgb((byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);

        public static Color Lerp(Color a, Color b, double t)
        {
            t = Math.Max(0, Math.Min(1, t));
            return Color.FromArgb(
                (byte)(a.A + (b.A - a.A) * t), (byte)(a.R + (b.R - a.R) * t),
                (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));
        }

        public static Color WithAlpha(Color c, double alpha) => Color.FromArgb((byte)Math.Round(255 * Math.Max(0, Math.Min(1, alpha))), c.R, c.G, c.B);

        // ------------------------------------------------------------ fonts

        public static FontFamily Body, Medium, Semi, Title, TitleSemi;

        /// <summary>The app's own fonts, unpacked next to the installer's temp files (WPF reads fonts from files).</summary>
        public static void LoadFonts()
        {
            string dir = null;
            try
            {
                dir = Path.Combine(Path.GetTempPath(), "VoltaSetup-fonts-" + Payload.SetupVersion);
                Directory.CreateDirectory(dir);
                var asm = Assembly.GetExecutingAssembly();
                foreach (var name in asm.GetManifestResourceNames().Where(n => n.StartsWith("font/")))
                {
                    var target = Path.Combine(dir, name.Substring(5));
                    using (var s = asm.GetManifestResourceStream(name))
                    {
                        if (File.Exists(target) && new FileInfo(target).Length == s.Length) continue;
                        using (var f = File.Create(target)) s.CopyTo(f);
                    }
                }
            }
            catch (Exception e)
            {
                Log.Line("fonts: " + e.Message);
            }
            Body = Font(dir, "onest-400.ttf", "Onest");
            Medium = Font(dir, "onest-500.ttf", "Onest");
            Semi = Font(dir, "onest-600.ttf", "Onest");
            Title = Font(dir, "unbounded-500.ttf", "Unbounded");
            TitleSemi = Font(dir, "unbounded-600.ttf", "Unbounded");
        }

        private static FontFamily Font(string dir, string file, string family)
        {
            try
            {
                if (dir != null && File.Exists(Path.Combine(dir, file)))
                {
                    var f = new FontFamily(new Uri(dir.TrimEnd('\\') + "\\"), "./" + file + "#" + family + ", Segoe UI");
                    if (f.GetTypefaces().Count > 0) return f;
                }
            }
            catch (Exception e)
            {
                Log.Line("font " + file + ": " + e.Message);
            }
            return new FontFamily("Segoe UI");
        }

        // ------------------------------------------------------------ control templates

        public static ResourceDictionary Styles { get; private set; }

        public static void LoadStyles()
        {
            Styles = (ResourceDictionary)XamlReader.Parse(StylesXaml);
        }

        public static Style GetStyle(string key) => (Style)Styles[key];

        private const string StylesXaml = @"
<ResourceDictionary xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'
                    xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'>

  <Style x:Key='Pill' TargetType='Button'>
    <Setter Property='Background' Value='#D08350'/>
    <Setter Property='Foreground' Value='#2B1306'/>
    <Setter Property='BorderBrush' Value='Transparent'/>
    <Setter Property='BorderThickness' Value='0'/>
    <Setter Property='Height' Value='44'/>
    <Setter Property='MinWidth' Value='128'/>
    <Setter Property='Padding' Value='26,0'/>
    <Setter Property='FontSize' Value='14.5'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='SnapsToDevicePixels' Value='True'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Grid>
            <Border x:Name='bg' CornerRadius='22' Background='{TemplateBinding Background}'
                    BorderBrush='{TemplateBinding BorderBrush}' BorderThickness='{TemplateBinding BorderThickness}'/>
            <Border x:Name='veil' CornerRadius='22' Background='#FFFFFF' Opacity='0'/>
            <Border x:Name='ring' CornerRadius='24' Margin='-3' BorderBrush='#F1B88E' BorderThickness='1.5' Opacity='0'/>
            <ContentPresenter Margin='{TemplateBinding Padding}' HorizontalAlignment='Center' VerticalAlignment='Center'
                              TextElement.Foreground='{TemplateBinding Foreground}' RecognizesAccessKey='True'/>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'>
              <Setter TargetName='veil' Property='Opacity' Value='0.10'/>
            </Trigger>
            <Trigger Property='IsPressed' Value='True'>
              <Setter TargetName='veil' Property='Background' Value='#000000'/>
              <Setter TargetName='veil' Property='Opacity' Value='0.14'/>
            </Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'>
              <Setter TargetName='ring' Property='Opacity' Value='0.9'/>
            </Trigger>
            <Trigger Property='IsEnabled' Value='False'>
              <Setter Property='Opacity' Value='0.4'/>
              <Setter Property='Cursor' Value='Arrow'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='Ghost' TargetType='Button' BasedOn='{StaticResource Pill}'>
    <Setter Property='Background' Value='Transparent'/>
    <Setter Property='Foreground' Value='#EEE8DF'/>
    <Setter Property='BorderBrush' Value='#3A434A'/>
    <Setter Property='BorderThickness' Value='1'/>
  </Style>

  <Style x:Key='Danger' TargetType='Button' BasedOn='{StaticResource Pill}'>
    <Setter Property='Background' Value='#E46F5C'/>
    <Setter Property='Foreground' Value='#2A0B05'/>
  </Style>

  <Style x:Key='IconButton' TargetType='Button'>
    <Setter Property='Width' Value='34'/>
    <Setter Property='Height' Value='34'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Foreground' Value='#8E979E'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='bg' CornerRadius='17' Background='Transparent'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'>
              <Setter TargetName='bg' Property='Background' Value='#22282D'/>
              <Setter Property='Foreground' Value='#EEE8DF'/>
            </Trigger>
            <Trigger Property='IsEnabled' Value='False'>
              <Setter Property='Opacity' Value='0.3'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='Switch' TargetType='CheckBox'>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Foreground' Value='#EEE8DF'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='CheckBox'>
          <Grid Background='Transparent'>
            <Grid.ColumnDefinitions>
              <ColumnDefinition Width='*'/>
              <ColumnDefinition Width='Auto'/>
            </Grid.ColumnDefinitions>
            <ContentPresenter VerticalAlignment='Center' Margin='0,0,18,0'/>
            <Border x:Name='track' Grid.Column='1' Width='46' Height='26' CornerRadius='13'
                    Background='#2D343A' BorderBrush='#3A434A' BorderThickness='1' VerticalAlignment='Center'>
              <Ellipse x:Name='thumb' Width='18' Height='18' Fill='#8E979E' HorizontalAlignment='Left' Margin='3,0'/>
            </Border>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='track' Property='Background' Value='#D08350'/>
              <Setter TargetName='track' Property='BorderBrush' Value='#D08350'/>
              <Setter TargetName='thumb' Property='Fill' Value='#FFF6EE'/>
              <Setter TargetName='thumb' Property='HorizontalAlignment' Value='Right'/>
            </Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'>
              <Setter TargetName='track' Property='BorderBrush' Value='#F1B88E'/>
            </Trigger>
            <Trigger Property='IsMouseOver' Value='True'>
              <Setter TargetName='track' Property='Opacity' Value='0.92'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='Field' TargetType='TextBox'>
    <Setter Property='Foreground' Value='#EEE8DF'/>
    <Setter Property='CaretBrush' Value='#F1B88E'/>
    <Setter Property='SelectionBrush' Value='#D08350'/>
    <Setter Property='FontSize' Value='14'/>
    <Setter Property='Height' Value='44'/>
    <Setter Property='VerticalContentAlignment' Value='Center'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='TextBox'>
          <Border x:Name='box' CornerRadius='14' Background='#22282D' BorderBrush='#2D343A' BorderThickness='1' Padding='14,0'>
            <ScrollViewer x:Name='PART_ContentHost' VerticalAlignment='Center' Focusable='False'
                          HorizontalScrollBarVisibility='Hidden' VerticalScrollBarVisibility='Disabled'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsKeyboardFocusWithin' Value='True'>
              <Setter TargetName='box' Property='BorderBrush' Value='#D08350'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
</ResourceDictionary>";
    }
}
