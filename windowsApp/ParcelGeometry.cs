using System.Numerics;
using System.Text.Json.Nodes;

namespace Boxlet.Windows;

/// <summary>Reads the same projection and palette as the Android/Compose exporters.</summary>
public sealed class ParcelGeometry
{
    readonly Vector2 top;
    readonly Vector2 axisU;
    readonly Vector2 axisV;
    readonly float height;
    readonly float openAngle;
    public IReadOnlyDictionary<string, string> Palette { get; }
    public Vector2[] Left { get; }
    public Vector2[] Right { get; }
    public Vector2[] Interior { get; }
    public Vector2[] Outline { get; }
    public Vector2[] TopTape { get; }
    public Vector2[] FrontTape { get; }

    public ParcelGeometry(Stream source)
    {
        var spec = JsonNode.Parse(source)!.AsObject();
        static Vector2 Read(JsonNode value) => new(value[0]!.GetValue<float>(), value[1]!.GetValue<float>());
        top = Read(spec["top"]!); axisU = Read(spec["axisU"]!); axisV = Read(spec["axisV"]!);
        height = spec["height"]!.GetValue<float>(); openAngle = spec["openAngle"]!.GetValue<float>() * MathF.PI / 180;
        Palette = spec["palette"]!.AsObject().ToDictionary(pair => pair.Key, pair => pair.Value!.GetValue<string>());
        var lo = .5f - spec["tapeHalfWidth"]!.GetValue<float>();
        var hi = 1f - lo;
        Left = [Project(0, 1), Project(1, 1), Project(1, 1, -1), Project(0, 1, -1)];
        Right = [Project(1, 1), Project(1, 0), Project(1, 0, -1), Project(1, 1, -1)];
        Interior = [Project(0, 0), Project(1, 0), Project(1, 1), Project(0, 1)];
        Outline = [Project(0, 0), Project(1, 0), Project(1, 0, -1), Project(1, 1, -1), Project(0, 1, -1), Project(0, 1)];
        TopTape = [Project(lo, 0), Project(hi, 0), Project(hi, 1), Project(lo, 1)];
        FrontTape = [Project(lo, 1), Project(hi, 1), Project(hi, 1, -.38f), Project(lo, 1, -.38f)];
    }
    Vector2 Project(float u, float v, float z = 0) => top + axisU * u + axisV * v - new Vector2(0, height * z);
    public (Vector2[] Rear, Vector2[] Front) Lids(float closed)
    {
        var angle = (1 - Math.Clamp(closed, 0, 1)) * openAngle;
        var depth = MathF.Cos(angle) * .5f;
        var lift = MathF.Sin(angle) * .5f;
        return ([Project(0, 0), Project(1, 0), Project(1, depth, lift), Project(0, depth, lift)],
            [Project(0, 1), Project(1, 1), Project(1, 1 - depth, lift), Project(0, 1 - depth, lift)]);
    }
}
