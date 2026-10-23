using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;

namespace Boxlet.Windows;

/// <summary>Owns one JVM engine. stdout is exclusively the JSON-lines protocol.</summary>
public sealed class EngineClient : IAsyncDisposable
{
    readonly ConcurrentDictionary<string, TaskCompletionSource<JsonNode?>> pending = new();
    readonly SemaphoreSlim writes = new(1, 1);
    Process? process;
    Task? reader;
    Task? diagnostics;
    bool stopping;
    public event Action<JsonObject>? StateReceived;
    public event Action<string>? Failed;

    public void Start(string dataDirectory)
    {
        var lib = Path.Combine(AppContext.BaseDirectory, "engine", "lib");
        if (!Directory.Exists(lib)) throw new DirectoryNotFoundException("找不到传输引擎，请使用 windowsApp/run.ps1 构建并启动。");
        var javaHome = Environment.GetEnvironmentVariable("BOXLET_JAVA_HOME") ?? Environment.GetEnvironmentVariable("JAVA_HOME");
        var bundledJava = Path.Combine(AppContext.BaseDirectory, "runtime", "bin", "java.exe");
        var java = File.Exists(bundledJava) ? bundledJava : javaHome is null ? "java" : Path.Combine(javaHome, "bin", "java.exe");
        var info = new ProcessStartInfo(java)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var argument in new[] { "-Dfile.encoding=UTF-8", "-cp", Path.Combine(lib, "*"), "dev.boxlet.engine.MainKt", "--desktop", "--data-dir", dataDirectory })
            info.ArgumentList.Add(argument);
        process = Process.Start(info) ?? throw new IOException("无法启动传输引擎。");
        reader = ReadAsync(process);
        diagnostics = Task.Run(async () =>
        {
            while (await process.StandardError.ReadLineAsync() is { } line)
                Debug.WriteLine($"[engine] {line}");
        });
    }

    async Task ReadAsync(Process engine)
    {
        Exception? failure = null;
        try
        {
            while (await engine.StandardOutput.ReadLineAsync() is { } line)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                var message = JsonNode.Parse(line) as JsonObject ?? throw new IOException("引擎返回了无效消息。");
                if (message["event"]?.ToString() == "state" && message["data"] is JsonObject state)
                    StateReceived?.Invoke(state);
                else if (message["id"]?.ToString() is { } id && pending.TryRemove(id, out var completion))
                {
                    if (message["ok"]?.GetValue<bool>() == true) completion.TrySetResult(message["result"]?.DeepClone());
                    else completion.TrySetException(new IOException(message["error"]?["message"]?.ToString() ?? "引擎操作失败。"));
                }
            }
        }
        catch (Exception e) { failure = e; }
        finally
        {
            foreach (var entry in pending.ToArray())
                if (pending.TryRemove(entry.Key, out var completion)) completion.TrySetException(failure ?? new IOException("传输引擎已退出。"));
            if (!stopping) Failed?.Invoke(failure?.Message ?? "传输引擎意外退出，请重新启动应用。");
        }
    }

    public async Task<JsonNode?> CallAsync(string command, object? parameters = null)
    {
        if (process is null || process.HasExited) throw new IOException("传输引擎未运行。");
        var id = Guid.NewGuid().ToString("N");
        var completion = new TaskCompletionSource<JsonNode?>(TaskCreationOptions.RunContinuationsAsynchronously);
        pending[id] = completion;
        try
        {
            var request = new JsonObject { ["id"] = id, ["command"] = command };
            if (parameters is not null) request["params"] = System.Text.Json.JsonSerializer.SerializeToNode(parameters);
            await writes.WaitAsync();
            try { await process.StandardInput.WriteLineAsync(request.ToJsonString()); await process.StandardInput.FlushAsync(); }
            finally { writes.Release(); }
            return await completion.Task.WaitAsync(TimeSpan.FromSeconds(30));
        }
        finally { pending.TryRemove(id, out _); }
    }

    public async ValueTask DisposeAsync()
    {
        if (stopping) return;
        stopping = true;
        var engine = process;
        if (engine is null) return;
        if (!engine.HasExited)
        {
            try { await CallAsync("shutdown").WaitAsync(TimeSpan.FromSeconds(3)); } catch { }
            engine.StandardInput.Close();
            try { await engine.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3)); }
            catch { if (!engine.HasExited) engine.Kill(entireProcessTree: true); }
        }
        if (reader is not null) await reader;
        if (diagnostics is not null) await diagnostics;
        engine.Dispose();
    }
}
