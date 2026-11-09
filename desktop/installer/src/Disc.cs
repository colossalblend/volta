using System;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using M = Volta.Setup.Theme.Metals;

namespace Volta.Setup
{
    /// <summary>
    /// The copper power disc from Volta's home screen (desktop/…/ConnectButton.kt), drawn the
    /// same way: lit copper with the rings of a turned blank, a bare copper band, the power
    /// mark cut into the metal and a bevelled rim; around it the field rings and, while
    /// working, the spark running round the edge. [Patina] turns it the green of «done».
    /// </summary>
    internal sealed class Disc : FrameworkElement
    {
        public static readonly DependencyProperty PhaseProperty = Register("Phase", 0.0);
        public static readonly DependencyProperty SparkProperty = Register("Spark", 0.0);
        public static readonly DependencyProperty SparkAlphaProperty = Register("SparkAlpha", 0.0);
        public static readonly DependencyProperty FieldAlphaProperty = Register("FieldAlpha", 1.0);
        public static readonly DependencyProperty PatinaProperty = Register("Patina", 0.0);
        public static readonly DependencyProperty DimProperty = Register("Dim", 0.0);

        private static DependencyProperty Register(string name, double value) =>
            DependencyProperty.Register(name, typeof(double), typeof(Disc),
                new FrameworkPropertyMetadata(value, FrameworkPropertyMetadataOptions.AffectsRender));

        public double Phase { get => (double)GetValue(PhaseProperty); set => SetValue(PhaseProperty, value); }
        public double Spark { get => (double)GetValue(SparkProperty); set => SetValue(SparkProperty, value); }
        public double SparkAlpha { get => (double)GetValue(SparkAlphaProperty); set => SetValue(SparkAlphaProperty, value); }
        public double FieldAlpha { get => (double)GetValue(FieldAlphaProperty); set => SetValue(FieldAlphaProperty, value); }
        public double Patina { get => (double)GetValue(PatinaProperty); set => SetValue(PatinaProperty, value); }
        public double Dim { get => (double)GetValue(DimProperty); set => SetValue(DimProperty, value); }

        private const double DiscShare = 0.62;
        private const int Rings = 3;

        /// <summary>The slow field rings and (when [working]) the spark: light, 30 frames a second.</summary>
        public void Animate(bool working)
        {
            var phase = new DoubleAnimation(0, 1, TimeSpan.FromSeconds(working ? 2.4 : 4.8)) { RepeatBehavior = RepeatBehavior.Forever };
            Timeline.SetDesiredFrameRate(phase, 30);
            BeginAnimation(PhaseProperty, phase);
            if (working)
            {
                var spark = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1.6)) { RepeatBehavior = RepeatBehavior.Forever };
                Timeline.SetDesiredFrameRate(spark, 30);
                BeginAnimation(SparkProperty, spark);
                BeginAnimation(SparkAlphaProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(300)));
            }
            else
            {
                BeginAnimation(SparkProperty, null);
                BeginAnimation(SparkAlphaProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(300)));
            }
        }

        public void Stop()
        {
            BeginAnimation(PhaseProperty, null);
            BeginAnimation(SparkProperty, null);
            SparkAlpha = 0;
        }

        public void ToPatina(bool animate)
        {
            if (animate) BeginAnimation(PatinaProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(900)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } });
            else Patina = 1;
        }

        protected override void OnRender(DrawingContext dc)
        {
            double outer = Math.Min(ActualWidth, ActualHeight) / 2;
            if (outer <= 0) return;
            var c = new Point(ActualWidth / 2, ActualHeight / 2);
            double r = outer * DiscShare;
            double p = Patina;

            // Its shadow.
            var shadow = new RadialGradientBrush(Color.FromArgb(120, 0, 0, 0), Colors.Transparent);
            dc.DrawEllipse(shadow, null, new Point(c.X, c.Y + r * 0.14), r * 1.28, r * 1.28);

            // Field rings.
            var ringColor = Theme.Lerp(Theme.Copper, M.PatinaLight, p);
            for (int i = 0; i < Rings; i++)
            {
                double t = (Phase + i / (double)Rings) % 1.0;
                double fade = (1 - t) * (1 - t);
                double alpha = 0.34 * fade * FieldAlpha;
                if (alpha < 0.01) continue;
                double rr = r + (outer - r) * t;
                dc.DrawEllipse(null, new Pen(new SolidColorBrush(Theme.WithAlpha(ringColor, alpha)), 1.2), c, rr, rr);
            }

            // The metal: copper, or the patina it turns into.
            var light = Theme.Lerp(M.CopperLight, M.PatinaLight, p);
            var mid = Theme.Lerp(M.Copper, M.Patina, p);
            var dark = Theme.Lerp(M.CopperDark, M.PatinaDark, p);
            var lit = new Point(c.X - r * 0.35, c.Y - r * 0.42);
            var metal = new RadialGradientBrush
            {
                MappingMode = BrushMappingMode.Absolute,
                Center = lit,
                GradientOrigin = lit,
                RadiusX = r * 1.7,
                RadiusY = r * 1.7,
                GradientStops = { new GradientStop(light, 0), new GradientStop(mid, 0.55), new GradientStop(dark, 1) }
            };
            dc.DrawEllipse(metal, null, c, r, r);
            const int turns = 16;
            var whiteTurn = new Pen(new SolidColorBrush(Color.FromArgb(10, 255, 255, 255)), 0.8);
            var blackTurn = new Pen(new SolidColorBrush(Color.FromArgb(11, 0, 0, 0)), 0.8);
            for (int k = 1; k <= turns; k++)
            {
                double rk = r * k / turns;
                dc.DrawEllipse(null, k % 2 == 0 ? whiteTurn : blackTurn, c, rk, rk);
            }
            // A soft sheen across the top left, like light on a coin.
            var sheen = new LinearGradientBrush
            {
                StartPoint = new Point(0, 0),
                EndPoint = new Point(1, 1),
                GradientStops =
                {
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.18),
                    new GradientStop(Color.FromArgb(26, 255, 255, 255), 0.32),
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.46),
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.62),
                    new GradientStop(Color.FromArgb(16, 255, 255, 255), 0.74),
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.86)
                }
            };
            dc.DrawEllipse(sheen, null, c, r, r);

            // The bare copper band at the edge.
            double band = r * 0.06;
            var bandBrush = new LinearGradientBrush
            {
                MappingMode = BrushMappingMode.Absolute,
                StartPoint = new Point(c.X - r, c.Y - r),
                EndPoint = new Point(c.X + r, c.Y + r),
                GradientStops = { new GradientStop(M.CopperLight, 0), new GradientStop(M.Copper, 0.5), new GradientStop(M.CopperDark, 1) }
            };
            dc.DrawEllipse(null, new Pen(bandBrush, band), c, r - band / 2, r - band / 2);

            // The power mark, cut in: a lit lower lip, then the dark stroke.
            var ink = Theme.Lerp(M.CopperInk, M.PatinaInk, p);
            PowerMark(dc, c, r, new Vector(0, 1.2), Color.FromArgb(56, 255, 255, 255));
            PowerMark(dc, c, r, new Vector(0, 0), Theme.WithAlpha(ink, 0.88));

            // The bevel.
            var rim = new LinearGradientBrush
            {
                MappingMode = BrushMappingMode.Absolute,
                StartPoint = new Point(c.X - r, c.Y - r),
                EndPoint = new Point(c.X + r, c.Y + r),
                GradientStops =
                {
                    new GradientStop(Color.FromArgb(90, 255, 255, 255), 0),
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.5),
                    new GradientStop(Color.FromArgb(90, 0, 0, 0), 1)
                }
            };
            dc.DrawEllipse(null, new Pen(rim, 1.5), c, r - 0.75, r - 0.75);

            if (Dim > 0) dc.DrawEllipse(new SolidColorBrush(Theme.WithAlpha(Theme.Background, Dim * 0.5)), null, c, r, r);

            if (SparkAlpha > 0.01) DrawSpark(dc, c, r + 8, Spark, SparkAlpha);
        }

        private static Point On(Point c, double radius, double degrees)
        {
            double a = degrees * Math.PI / 180;
            return new Point(c.X + Math.Cos(a) * radius, c.Y + Math.Sin(a) * radius);
        }

        private static void PowerMark(DrawingContext dc, Point c, double r, Vector offset, Color color)
        {
            double radius = r * 0.30;
            double stroke = r * 0.085;
            var pen = new Pen(new SolidColorBrush(color), stroke) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
            var cc = c + offset;
            var fig = new PathFigure { StartPoint = On(cc, radius, -58), IsClosed = false, IsFilled = false };
            fig.Segments.Add(new ArcSegment(On(cc, radius, -58 + 296), new Size(radius, radius), 0, true, SweepDirection.Clockwise, true));
            dc.DrawGeometry(null, pen, new PathGeometry(new[] { fig }));
            dc.DrawLine(pen, new Point(cc.X, cc.Y - radius * 1.18), new Point(cc.X, cc.Y - radius * 0.28));
        }

        private static void DrawSpark(DrawingContext dc, Point c, double orbit, double angle, double alpha)
        {
            const int steps = 28;
            const double span = 120;
            for (int i = 0; i < steps; i++)
            {
                double a = angle - 90 - span * (i + 1) / steps;
                var pen = new Pen(new SolidColorBrush(Theme.WithAlpha(M.CopperLight, alpha * 0.85 * (1 - i / (double)steps))), 2)
                {
                    StartLineCap = PenLineCap.Round,
                    EndLineCap = PenLineCap.Round
                };
                var fig = new PathFigure { StartPoint = On(c, orbit, a), IsClosed = false, IsFilled = false };
                fig.Segments.Add(new ArcSegment(On(c, orbit, a + span / steps + 0.6), new Size(orbit, orbit), 0, false, SweepDirection.Clockwise, true));
                dc.DrawGeometry(null, pen, new PathGeometry(new[] { fig }));
            }
            var dot = On(c, orbit, angle - 90);
            var glow = new RadialGradientBrush(Theme.WithAlpha(M.Spark, 0.55 * alpha), Colors.Transparent);
            dc.DrawEllipse(glow, null, dot, 14, 14);
            dc.DrawEllipse(new SolidColorBrush(Theme.WithAlpha(M.Spark, alpha)), null, dot, 3.5, 3.5);
        }
    }
}
