using System.Diagnostics;
using System.Numerics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.UI;
using Windows.UI.ViewManagement;
using ShapePath = Microsoft.UI.Xaml.Shapes.Path;

namespace Boxlet.Windows;

/// <summary>Native WinUI vector parcel, with finite selection-change animations.</summary>
public sealed class ParcelView : UserControl
{
    static readonly ParcelGeometry geometry = LoadGeometry();
    readonly Canvas drawing = new() { Width = 64, Height = 64, IsHitTestVisible = false };
    readonly Canvas body = new();
    readonly Canvas lids = new();
    readonly Canvas seal = new();
    readonly ShapePath inside;
    readonly ShapePath rear;
    readonly ShapePath front;
    readonly CompositeTransform motion = new();
    readonly UISettings uiSettings = new();
    readonly Stopwatch clock = new();
    int count = -1;
    float closed;
    float fromClosed;
    float targetClosed;
    bool animating;

    public ParcelView()
    {
        Width = Height = 64; Content = drawing;
        AutomationProperties.SetName(this, "Boxlet 包裹");
        drawing.RenderTransform = motion; drawing.RenderTransformOrigin = new Point(.5, .5);
        var shadow = new Microsoft.UI.Xaml.Shapes.Ellipse { Width = 48.64, Height = 9.6,
            Fill = new SolidColorBrush(Color.FromArgb(20, 73, 125, 169)) };
        Canvas.SetLeft(shadow, 7.68); Canvas.SetTop(shadow, 51.2); drawing.Children.Add(shadow);
        drawing.Children.Add(body);
        body.Children.Add(Face(geometry.Left, Gradient("left", "leftShade")));
        body.Children.Add(Face(geometry.Right, Gradient("right", "rightShade")));
        inside = Face(geometry.Interior, new SolidColorBrush(ColorOf("interior"))); body.Children.Add(inside);
        drawing.Children.Add(lids);
        rear = Face([], Gradient("top", "topShade")); front = Face([], Gradient("top", "topShade"));
        lids.Children.Add(rear); lids.Children.Add(front);
        seal.Children.Add(Face(geometry.TopTape, new SolidColorBrush(ColorOf("tape")), .004f));
        seal.Children.Add(Face(geometry.FrontTape, new SolidColorBrush(ColorOf("tape")), .004f));
        drawing.Children.Add(seal);
        Unloaded += (_, _) => { Stop(); Draw(targetClosed, 0); };
        Draw(0, 0);
    }
    static ParcelGeometry LoadGeometry()
    {
        using var stream = typeof(ParcelView).Assembly.GetManifestResourceStream("Boxlet.Parcel.json")
            ?? throw new InvalidOperationException("Missing shared parcel geometry.");
        return new ParcelGeometry(stream);
    }
    static Color ColorOf(string name)
    {
        var value = Convert.ToUInt32(geometry.Palette[name][1..], 16);
        return Color.FromArgb(255, (byte)(value >> 16), (byte)(value >> 8), (byte)value);
    }
    static LinearGradientBrush Gradient(string light, string shade) => new() {
        MappingMode = BrushMappingMode.Absolute,
        StartPoint = new Point(64 * .2, 64 * .22), EndPoint = new Point(64 * .8, 64 * .85),
        GradientStops = { new GradientStop { Color = ColorOf(light) }, new GradientStop { Color = ColorOf(shade), Offset = 1 } },
    };
    static ShapePath Face(Vector2[] points, Brush brush, float cut = 0) => new() { Data = Shape(points, cut), Fill = brush };
    static PathGeometry Shape(Vector2[] points, float cut = 0)
    {
        var path = new PathGeometry();
        if (points.Length == 0) return path;
        static Point Pixel(Vector2 p) => new(p.X * 64, p.Y * 64);
        Vector2 Toward(Vector2 vertex, Vector2 other) {
            var length = Vector2.Distance(vertex, other);
            return Vector2.Lerp(vertex, other, length == 0 ? 0 : Math.Min(cut / length, .45f));
        }
        var figure = new PathFigure { IsClosed = true, IsFilled = true };
        for (var i = 0; i < points.Length; i++) {
            var vertex = points[i];
            var entry = Toward(vertex, points[(i + points.Length - 1) % points.Length]);
            var exit = Toward(vertex, points[(i + 1) % points.Length]);
            if (i == 0) figure.StartPoint = Pixel(entry); else figure.Segments.Add(new LineSegment { Point = Pixel(entry) });
            if (cut > 0) figure.Segments.Add(new QuadraticBezierSegment { Point1 = Pixel(vertex), Point2 = Pixel(exit) });
        }
        path.Figures.Add(figure); return path;
    }
    public void SetItemCount(int next)
    {
        if (next == count) return;
        var initial = count < 0;
        count = next; fromClosed = closed; targetClosed = next > 0 ? 1 : 0;
        AutomationProperties.SetName(this, next > 0 ? $"已打包 {next} 项内容" : "空包裹");
        if (initial || !uiSettings.AnimationsEnabled) { Stop(); Draw(targetClosed, 0); return; }
        clock.Restart();
        if (!animating) { CompositionTarget.Rendering += RenderFrame; animating = true; }
    }
    void RenderFrame(object? sender, object args)
    {
        if (!uiSettings.AnimationsEnabled) { Stop(); Draw(targetClosed, 0); return; }
        var seconds = clock.Elapsed.TotalSeconds;
        var fold = Ease(Math.Clamp(seconds / .480, 0, 1));
        var bounce = seconds < .130 ? Ease(seconds / .130) : Spring(seconds - .130);
        Draw(fromClosed + (targetClosed - fromClosed) * (float)fold, bounce);
        if (seconds >= .900) { Stop(); Draw(targetClosed, 0); }
    }
    // Compose's default tween easing: cubic-bezier(.4, 0, .2, 1).
    static double Ease(double progress)
    {
        var lo = 0.0; var hi = 1.0;
        for (var i = 0; i < 16; i++) {
            var t = (lo + hi) / 2;
            var x = 3 * (1 - t) * (1 - t) * t * .4 + 3 * (1 - t) * t * t * .2 + t * t * t;
            if (x < progress) lo = t; else hi = t;
        }
        var p = (lo + hi) / 2; return 3 * (1 - p) * p * p + p * p * p;
    }
    static double Spring(double seconds)
    {
        const double damping = .45;
        var omega = Math.Sqrt(380); var damped = omega * Math.Sqrt(1 - damping * damping);
        return Math.Exp(-damping * omega * seconds) * (Math.Cos(damped * seconds) + damping * omega / damped * Math.Sin(damped * seconds));
    }
    void Draw(float fold, double bounce)
    {
        closed = fold;
        var (back, fore) = geometry.Lids(fold);
        rear.Data = Shape(back, .008f * (1 - fold)); front.Data = Shape(fore, .008f * (1 - fold));
        var a = ColorOf("interior"); var b = ColorOf("top");
        inside.Fill = new SolidColorBrush(Color.FromArgb(255, (byte)(a.R + (b.R - a.R) * fold), (byte)(a.G + (b.G - a.G) * fold), (byte)(a.B + (b.B - a.B) * fold)));
        seal.Opacity = Math.Clamp((fold - .86f) / .14f, 0, 1);
        motion.TranslateY = -3 * bounce; motion.Rotation = -3 * bounce;
        motion.ScaleX = motion.ScaleY = 1 + .035 * bounce;
    }
    void Stop() { if (animating) CompositionTarget.Rendering -= RenderFrame; animating = false; clock.Stop(); }
}
