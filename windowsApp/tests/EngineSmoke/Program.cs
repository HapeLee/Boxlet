using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Boxlet.Windows;

var root = Path.Combine(Path.GetTempPath(), "boxlet-ipc-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
var listener = new TcpListener(IPAddress.Loopback, 0);
listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
await File.WriteAllTextAsync(Path.Combine(root, "settings.json"), new JsonObject { ["port"] = port, ["alias"] = "Native IPC test", ["https"] = false }.ToJsonString());
try
{
    var engine = new EngineClient();
    var state = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
    engine.StateReceived += value => { if (value["serverRunning"]?.GetValue<bool>() == true) state.TrySetResult(value); };
    await using (engine)
    {
        engine.Start(root);
        await state.Task.WaitAsync(TimeSpan.FromSeconds(20));
        var responses = await Task.WhenAll(Enumerable.Range(0, 12).Select(_ => engine.CallAsync("status")));
        if (responses.Any(r => r?["alias"]?.ToString() != "Native IPC test")) throw new Exception("Response correlation failed");
        await engine.CallAsync("settings.set", new { alias = "中文 IPC ✓" });
        var settings = await engine.CallAsync("settings.get");
        if (settings?["alias"]?.ToString() != "中文 IPC ✓") throw new Exception("UTF-8 settings round trip failed: " + settings?.ToJsonString());
        try { await engine.CallAsync("unknown.test.command"); throw new Exception("Expected protocol failure"); }
        catch (IOException e) when (e.Message.Contains("Unknown command")) { }
        var bytes = RandomNumberGenerator.GetBytes(2 * 1024 * 1024);
        var file = Path.Combine(root, "中文 payload.bin"); await File.WriteAllBytesAsync(file, bytes);
        var share = await engine.CallAsync("share.start", new { files = new[] { new { path = file } } });
        using var http = new HttpClient(new HttpClientHandler { UseProxy = false });
        var url = share?["url"]?.ToString() ?? throw new Exception("Missing share URL");
        var page = await http.GetStringAsync(url);
        if (!page.Contains("中文 payload.bin")) throw new Exception("Shared file missing from HTML");
        // The share service exposes the same LocalSend prepare-download API.
        var response = await http.PostAsync(new Uri(new Uri(url), "api/localsend/v2/prepare-download"), new StringContent("{}", System.Text.Encoding.UTF8, "application/json"));
        response.EnsureSuccessStatusCode();
        var download = JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
        var entry = download["files"]!.AsObject().First();
        var downloadUrl = new Uri(new Uri(url), $"api/localsend/v2/download?sessionId={Uri.EscapeDataString(download["sessionId"]!.ToString())}&fileId={Uri.EscapeDataString(entry.Key)}");
        var received = await http.GetByteArrayAsync(downloadUrl);
        if (!SHA256.HashData(bytes).SequenceEqual(SHA256.HashData(received))) throw new Exception("Download hash mismatch");
        await engine.CallAsync("share.stop");
        var bridge = await engine.CallAsync("bridge.start", new { port = 0 });
        if (bridge?["url"]?.ToString().StartsWith("ws://") != true) throw new Exception("Bridge start failed");
        await engine.CallAsync("bridge.stop");
    }
    Console.WriteLine("PASS: concurrent IPC, UTF-8 settings, errors, 2 MiB share SHA-256, bridge lifecycle, graceful shutdown.");
}
finally { Directory.Delete(root, recursive: true); }
