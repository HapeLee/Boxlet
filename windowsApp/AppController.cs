using System.Diagnostics;
using System.Text.Json.Nodes;
using Microsoft.UI.Dispatching;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace Boxlet.Windows;

public sealed record SelectedFile(string Path, string Name, long Size);

public sealed class AppController
{
    readonly DispatcherQueue dispatcher = DispatcherQueue.GetForCurrentThread();
    readonly EngineClient engine = new();
    public JsonObject State { get; private set; } = new();
    public JsonObject Settings { get; private set; } = new();
    public JsonArray History { get; private set; } = new();
    public JsonArray Favorites { get; private set; } = new();
    public List<SelectedFile> Files { get; } = new();
    public Dictionary<string, string> Drafts { get; } = new();
    public string? SelectedText { get; private set; }
    public string Notice { get; private set; } = "正在启动传输引擎…";
    // 空内容时点了设备才提示，避免卡片上常驻一句「先选择内容」。
    public bool SendHint { get; private set; }
    public bool Busy { get; private set; }
    public bool Connected { get; private set; }
    public string Pin { get; set; } = "";
    public long Version { get; private set; }
    public event Action? Changed;
    public readonly string DataDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Boxlet");
    // Known-folder lookup so a relocated 「下载」(e.g. D:\Downloads) is honoured; the engine resolves the same path.
    public static string DefaultDestination { get; } = ResolveDefaultDestination();

    static string ResolveDefaultDestination()
    {
        try
        {
            var downloads = UserDataPaths.GetDefault().Downloads;
            if (!string.IsNullOrEmpty(downloads)) return Path.Combine(downloads, "Boxlet");
        }
        catch { }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Boxlet");
    }

    public Action Subscribe(Action changed) { Changed += changed; return () => Changed -= changed; }
    public void Notify() { Version++; Changed?.Invoke(); }
    public void Message(string text) { Notice = text; Notify(); }
    public void ClearSendHint() { if (SendHint) { SendHint = false; Notify(); } }
    public string Text(string name, string fallback = "") => State[name]?.ToString() ?? fallback;
    public bool Flag(string name) => Settings[name]?.GetValue<bool>() == true;
    public string Draft(string key) => Drafts.TryGetValue(key, out var text) ? text : SettingText(key);
    public string SettingText(string key) => Settings[key] is JsonArray patterns
        ? string.Join(", ", patterns.Select(pattern => pattern?.ToString())) : Settings[key]?.ToString() ?? "";
    public void Edit(string key, string value) { Drafts[key] = value; Notify(); }

    public async Task StartAsync()
    {
        Directory.CreateDirectory(DataDirectory);
        try
        {
            // Both desktop frontends use the same configuration. Never silently start a second owner.
            var settingsPath = Path.Combine(DataDirectory, "settings.json");
            var saved = File.Exists(settingsPath) ? JsonNode.Parse(await File.ReadAllTextAsync(settingsPath)) : null;
            var port = saved?["port"]?.GetValue<int>() ?? 53318;
            var bp2Enabled = saved?["bp2Enabled"]?.GetValue<bool>() ?? true;
            var ports = new HashSet<int> { bp2Enabled && port == 53318 ? 53317 : port };
            if (bp2Enabled) ports.Add(saved?["bp2Port"]?.GetValue<int>() ?? 53318);
            var occupied = System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties()
                .GetActiveTcpListeners().FirstOrDefault(endpoint => ports.Contains(endpoint.Port));
            if (occupied is not null) throw new IOException($"端口 {occupied.Port} 正被使用。请先从托盘退出原 Compose 客户端，再重新打开 Windows 客户端。");
            engine.StateReceived += state => dispatcher.TryEnqueue(() => { State = state; Notify(); });
            engine.Failed += error => dispatcher.TryEnqueue(() => { Connected = false; Message(error); });
            engine.Start(DataDirectory);
            await engine.CallAsync("status");
            Connected = true;
            await RefreshSettingsAsync();
            await RefreshListsAsync();
            Notice = "";
            Notify();
        }
        catch (Exception e) {
            await File.WriteAllTextAsync(Path.Combine(DataDirectory, "windows-client-startup.log"), e.ToString());
            Message(e.Message);
        }
    }

    public async Task RunAsync(Func<Task> operation)
    {
        if (Busy) return;
        Busy = true; Notify();
        try { await operation(); }
        catch (Exception e) { Message(e.Message); }
        finally { Busy = false; Notify(); }
    }
    public async Task CommandAsync(string command, object? parameters = null)
    {
        await engine.CallAsync(command, parameters);
    }
    public async Task RefreshSettingsAsync()
    {
        Settings = await engine.CallAsync("settings.get") as JsonObject ?? new();
        Drafts.Clear(); Notify();
    }
    public async Task RefreshListsAsync()
    {
        History = await engine.CallAsync("history.list") as JsonArray ?? new();
        Favorites = await engine.CallAsync("favorites.list") as JsonArray ?? new();
        Notify();
    }
    public async Task SaveAsync(string key, object? value)
    {
        await engine.CallAsync("settings.set", new Dictionary<string, object?> { [key] = value });
        await RefreshSettingsAsync();
        Message("设置已保存");
    }
    public async Task TrustBp2Async(string fingerprint, bool trusted)
    {
        await engine.CallAsync(trusted ? "bp2.trust-peer" : "bp2.untrust-peer", new { fingerprint = fingerprint.Trim().ToLowerInvariant() });
        await RefreshSettingsAsync();
        Message(trusted ? "已添加指纹；请在对端也添加本机指纹" : "已撤销配对");
    }
    public async Task ConnectBp2Async(string fingerprint, string address)
    {
        var endpoint = Bp2Endpoint.Parse(address);
        await engine.CallAsync("bp2.connect", new { fingerprint, host = endpoint.Host, port = endpoint.Port });
        Message("正在连接，请等待设备验证成功");
    }
    public string Bp2Status
    {
        get {
            if (!Flag("bp2Enabled")) return "已关闭";
            var adapter = State["protocols"]?.AsArray().FirstOrDefault(p => p?["adapterId"]?.ToString() == "boxlet-bp2");
            if (adapter is null) return "正在启动";
            if (adapter["sendReady"]?.GetValue<bool>() == true && adapter["receiveReady"]?.GetValue<bool>() == true)
                return adapter["discoveryReady"]?.GetValue<bool>() == true ? "已就绪 · 优先连接已配对设备" : "传输已就绪 · 自动发现不可用，可通过 IP 连接";
            return adapter["reason"]?.ToString() ?? "尚未就绪";
        }
    }
    public async Task ImportAsync(IEnumerable<string> paths)
    {
        var added = await Task.Run(() =>
        {
            var result = new List<SelectedFile>();
            foreach (var path in paths)
            {
                if (Directory.Exists(path))
                {
                    foreach (var file in Directory.EnumerateFiles(path, "*", new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = System.IO.FileAttributes.ReparsePoint | System.IO.FileAttributes.Hidden, IgnoreInaccessible = true }))
                        result.Add(new(file, Path.GetFileName(path) + "/" + Path.GetRelativePath(path, file).Replace('\\', '/'), new FileInfo(file).Length));
                }
                else if (File.Exists(path)) result.Add(new(path, Path.GetFileName(path), new FileInfo(path).Length));
            }
            return result;
        });
        var known = Files.Select(f => f.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
        Files.AddRange(added.Where(f => known.Add(f.Path)));
        SelectedText = null;
        SendHint = false;
        Message(Files.Count == 0 ? "所选目录中没有可发送的文件" : "");
    }
    public void SelectText(string value) { if (string.IsNullOrWhiteSpace(value)) return; SelectedText = value; Files.Clear(); SendHint = false; Message(""); }
    public void Clear() { Files.Clear(); SelectedText = null; SendHint = false; Notify(); }
    public async Task ClipboardAsync()
    {
        var data = Clipboard.GetContent();
        if (data.Contains(StandardDataFormats.StorageItems)) await ImportAsync((await data.GetStorageItemsAsync()).Select(item => item.Path));
        else if (data.Contains(StandardDataFormats.Text)) SelectText(await data.GetTextAsync());
        else Message("剪贴板中没有文件或文本。");
    }
    public async Task SendAsync(string fingerprint)
    {
        if (SelectedText is not null) await engine.CallAsync("send.text", new { fingerprint, text = SelectedText });
        else if (Files.Count > 0) await engine.CallAsync("send.files", new { fingerprint, files = Files.Select(f => new { path = f.Path, name = f.Name }).ToArray() });
        // 没选内容就点设备：提示直接出现在设备卡片上，不另占版面。
        else if (!SendHint) { SendHint = true; Notify(); }
    }
    public async Task ShareAsync()
    {
        if (Files.Count == 0) throw new InvalidOperationException("请先选择要通过浏览器分享的文件。");
        await engine.CallAsync("share.start", new { files = Files.Select(f => new { path = f.Path, name = f.Name }).ToArray() });
    }
    public async Task ToggleFavoriteAsync(JsonNode device)
    {
        var fingerprint = device["fingerprint"]!.ToString();
        if (Favorites.Any(f => f?["fingerprint"]?.ToString() == fingerprint)) await engine.CallAsync("favorites.remove", new { fingerprint });
        else
        {
            var channel = device["channels"]?.AsArray().FirstOrDefault();
            await engine.CallAsync("favorites.add", new { fingerprint, alias = device["alias"]?.ToString(), host = channel?["host"]?.ToString() ?? "", port = channel?["port"]?.GetValue<int>() ?? 53318, https = channel?["https"]?.GetValue<bool>() ?? true });
        }
        await RefreshListsAsync();
    }
    public Task StopAsync() => engine.DisposeAsync().AsTask();
    public static void Copy(string value) { var package = new DataPackage(); package.SetText(value); Clipboard.SetContent(package); }
    public static void OpenFolder(string path)
    {
        if (!Directory.Exists(path)) Directory.CreateDirectory(path);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{path}\"") { UseShellExecute = true });
    }
    public static void OpenFile(string path) => Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    // 在资源管理器里定位文件；文件已被删除时退回到所在目录。
    public static void RevealInExplorer(string path)
    {
        var folder = Path.GetDirectoryName(path);
        if (string.IsNullOrEmpty(folder)) return;
        var argument = File.Exists(path) ? $"/select,\"{path}\"" : $"\"{folder}\"";
        Process.Start(new ProcessStartInfo("explorer.exe", argument) { UseShellExecute = true });
    }
    public static string Bytes(long value) => value < 1024 ? $"{value} B" : value < 1048576 ? $"{value / 1024.0:F1} KB" : value < 1073741824 ? $"{value / 1048576.0:F1} MB" : $"{value / 1073741824.0:F2} GB";
}
