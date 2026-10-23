using System.Text.Json;
using Microsoft.UI.Reactor;
using Microsoft.UI.Reactor.Core;

namespace Boxlet.Windows;

/// <summary>Same Fluent vector paths as Compose; see Assets/FluentIcons-LICENSE.txt.</summary>
public static class BrandIcons
{
    static readonly IReadOnlyDictionary<string, string> paths = JsonSerializer.Deserialize<Dictionary<string, string>>(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Assets", "FluentIcons.json")))!;
    public static IconData Get(string name) => Factories.PathIcon(paths[name]);
}
