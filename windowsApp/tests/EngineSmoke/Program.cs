using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Boxlet.Windows;

var root = Path.Combine(Path.GetTempPath(), "boxlet-ipc-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
var listener = new TcpListener(IPAddress.Loopback, 0);
listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
await File.WriteAllTextAsync(Path.Combine(root, "settings.json"), new JsonObject { ["port"] = port, ["bp2Enabled"] = true, ["bp2Port"] = 0, ["alias"] = "Native IPC test", ["https"] = false }.ToJsonString());
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
        if (settings?["bp2Enabled"]?.GetValue<bool>() != false ||
            (await engine.CallAsync("status"))?["bp2Port"]?.GetValue<int>() != 0)
            throw new Exception("The product must migrate to LocalSend without starting BP/2");
        using var defaultHttp = new HttpClient(new HttpClientHandler { UseProxy = false });
        var localInfo = await defaultHttp.GetStringAsync($"http://127.0.0.1:{port}/api/localsend/v2/info");
        if (JsonNode.Parse(localInfo)?["sendeeCapabilities"] is JsonArray capabilities && capabilities.Count > 0)
            throw new Exception("The default LocalSend endpoint must not advertise private extensions");
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

        // Real native BP/2 transfer between two separate JVM processes, through the same
        // settings/discovery/send entry points used by the desktop shell, without manual pairing.
        var peerRoot = Path.Combine(root, "bp2-peer");
        var peerReceived = Path.Combine(peerRoot, "received");
        Directory.CreateDirectory(peerRoot);
        await File.WriteAllTextAsync(Path.Combine(peerRoot, "settings.json"), new JsonObject
        {
            ["port"] = 0, ["bp2Enabled"] = true, ["bp2Port"] = 0, ["https"] = false, ["protocolDefaultsVersion"] = 1,
            ["quickSave"] = true, ["alias"] = "BP2 IPC peer", ["destination"] = peerReceived,
        }.ToJsonString());
        await using var peer = new EngineClient();
        var peerReady = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        peer.StateReceived += value => { if (value["serverRunning"]?.GetValue<bool>() == true) peerReady.TrySetResult(value); };
        peer.Start(peerRoot);
        await peerReady.Task.WaitAsync(TimeSpan.FromSeconds(20));
        var peerStatus = await peer.CallAsync("status");
        var peerFingerprint = peerStatus?["fingerprint"]?.ToString() ?? throw new Exception("Missing peer fingerprint");
        await engine.CallAsync("settings.set", new { port = 0, bp2Enabled = true, bp2Port = 0 });
        peerStatus = await peer.CallAsync("status");
        var peerPort = peerStatus?["bp2Port"]?.GetValue<int>() ?? throw new Exception("Missing native port");
        try {
            await engine.CallAsync("bp2.connect", new { host = "127.0.0.1", port = peerPort, fingerprint = peerFingerprint, carrier = "retired-udp" });
            throw new InvalidOperationException("Removed transport selection must be rejected");
        } catch (IOException error) when (error.Message.Contains("only accepts")) { }
        var discovered = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        var nativeDone = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        engine.StateReceived += value =>
        {
            if (value["devices"] is JsonArray devices && devices.OfType<JsonObject>().Any(device =>
                device["fingerprint"]?.ToString() == peerFingerprint && device["channels"] is JsonArray channels &&
                channels.OfType<JsonObject>().Any(channel => channel["adapterId"]?.ToString() == "boxlet-bp2" &&
                    channel["identityVerified"]?.GetValue<bool>() == true))) discovered.TrySetResult(value);
            if (value["outgoing"] is JsonObject outgoing && outgoing["adapterId"]?.ToString() == "boxlet-bp2" &&
                outgoing["status"]?.ToString() is "completed" or "failed" or "declined") nativeDone.TrySetResult(outgoing);
        };
        await engine.CallAsync("bp2.connect", new { host = "127.0.0.1", port = peerPort, fingerprint = peerFingerprint });
        await discovered.Task.WaitAsync(TimeSpan.FromSeconds(20));
        var small = Path.Combine(root, "small.txt"); await File.WriteAllTextAsync(small, "BP/2 中文批量文件");
        var empty = Path.Combine(root, "empty.bin"); await File.WriteAllBytesAsync(empty, []);
        await engine.CallAsync("send.files", new { fingerprint = peerFingerprint,
            files = new[] { new { path = file }, new { path = small }, new { path = empty } } });
        var completed = await nativeDone.Task.WaitAsync(TimeSpan.FromSeconds(30));
        if (completed["status"]?.ToString() != "completed") throw new Exception("Native transfer failed: " + completed.ToJsonString());
        var nativeBytes = await File.ReadAllBytesAsync(Path.Combine(peerReceived, Path.GetFileName(file)));
        if (!SHA256.HashData(bytes).SequenceEqual(SHA256.HashData(nativeBytes))) throw new Exception("Native BP/2 SHA-256 mismatch");
        if (await File.ReadAllTextAsync(Path.Combine(peerReceived, "small.txt")) != "BP/2 中文批量文件") throw new Exception("Native batch mismatch");
        if (new FileInfo(Path.Combine(peerReceived, "empty.bin")).Length != 0) throw new Exception("Native empty file mismatch");
        JsonNode? history = null;
        for (var attempt = 0; attempt < 20; attempt++)
        {
            history = await peer.CallAsync("history.list");
            if (history is JsonArray entries && entries.Count == 3) break;
            await Task.Delay(50);
        }
        if (history is not JsonArray receivedHistory || receivedHistory.Count != 3 ||
            receivedHistory.Any(entry => entry?["adapterId"]?.ToString() != "boxlet-bp2")) throw new Exception("Native history protocol mismatch");

        // Keep the authenticated endpoint stable across a receiver process restart.
        await peer.CallAsync("settings.set", new { bp2Port = peerPort });
        var restartFile = Path.Combine(root, "restart-payload.bin");
        var restartSmall = Path.Combine(root, "restart-small.bin");
        await File.WriteAllBytesAsync(restartSmall, [1, 2, 3]);
        await using (var stream = File.Create(restartFile))
        {
            var block = RandomNumberGenerator.GetBytes(1024 * 1024);
            for (var index = 0; index < 256; index++) await stream.WriteAsync(block);
        }
        var restartProgress = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var restartDone = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        JsonObject? lastRestartTransfer = null;
        peer.StateReceived += value =>
        {
            if (value["incoming"] is JsonObject incoming && incoming["status"]?.ToString() == "transferring" &&
                incoming["transferredBytes"]?.GetValue<long>() >= 8L * 1024 * 1024 &&
                incoming["files"] is JsonArray entries && entries.Any(entry => entry?["fileName"]?.ToString() == "restart-payload.bin"))
                restartProgress.TrySetResult();
        };
        engine.StateReceived += value =>
        {
            if (value["outgoing"] is not JsonObject outgoing || outgoing["files"] is not JsonArray entries ||
                !entries.Any(entry => entry?["fileName"]?.ToString() == "restart-payload.bin")) return;
            lastRestartTransfer = outgoing;
            if (outgoing["status"]?.ToString() is "completed" or "failed" or "cancelled") restartDone.TrySetResult(outgoing);
        };
        await engine.CallAsync("send.files", new { fingerprint = peerFingerprint,
            files = new[] { new { path = restartSmall }, new { path = restartFile } } });
        await restartProgress.Task.WaitAsync(TimeSpan.FromSeconds(30));
        await peer.DisposeAsync();
        await using var restartedPeer = new EngineClient();
        var restartedReady = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        restartedPeer.StateReceived += value => { if (value["ready"]?.GetValue<bool>() == true) restartedReady.TrySetResult(); };
        restartedPeer.Start(peerRoot);
        await restartedReady.Task.WaitAsync(TimeSpan.FromSeconds(20));
        // A slow JVM startup may exhaust the three automatic retries; the retained task remains resumable.
        for (var attempt = 0; !restartDone.Task.IsCompleted && attempt < 300; attempt++)
        {
            if (lastRestartTransfer?["status"]?.ToString() == "paused" && lastRestartTransfer["error"]?.ToString()?.Contains("可继续") == true)
                await engine.CallAsync("transfer.continue", new { direction = "send" });
            await Task.Delay(100);
        }
        var recovered = await restartDone.Task.WaitAsync(TimeSpan.FromSeconds(30));
        if (recovered["status"]?.ToString() != "completed") throw new Exception("Restart resume failed: " + recovered.ToJsonString());
        await using var originalStream = File.OpenRead(restartFile);
        await using var recoveredStream = File.OpenRead(Path.Combine(peerReceived, "restart-payload.bin"));
        var originalHash = await SHA256.HashDataAsync(originalStream);
        var recoveredHash = await SHA256.HashDataAsync(recoveredStream);
        if (!originalHash.SequenceEqual(recoveredHash)) throw new Exception("Restart resume SHA-256 mismatch");
        for (var attempt = 0; attempt < 20; attempt++)
        {
            history = await restartedPeer.CallAsync("history.list");
            if (history is JsonArray entries && entries.Count == 5) break;
            await Task.Delay(50);
        }
        if (history is not JsonArray recoveredHistory || recoveredHistory.Count != 5 ||
            recoveredHistory.Select(entry => entry?["id"]?.ToString()).Distinct().Count() != 5 ||
            Directory.GetFiles(peerReceived, "*", SearchOption.AllDirectories).Length != 5)
            throw new Exception("Restart resume duplicated files or history");

        // Restart the sender while its selected sources and credentials are persisted privately.
        var senderProgress = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        engine.StateReceived += value =>
        {
            if (value["outgoing"] is JsonObject outgoing && outgoing["status"]?.ToString() == "transferring" &&
                outgoing["transferredBytes"]?.GetValue<long>() >= 8L * 1024 * 1024 && outgoing["files"] is JsonArray entries &&
                entries.Any(entry => entry?["fileName"]?.ToString() == "sender-restart-payload.bin")) senderProgress.TrySetResult();
        };
        await engine.CallAsync("send.files", new { fingerprint = peerFingerprint, files = new[] {
            new { path = restartSmall, name = "sender-restart-small.bin" }, new { path = restartFile, name = "sender-restart-payload.bin" } } });
        await senderProgress.Task.WaitAsync(TimeSpan.FromSeconds(30));
        await engine.DisposeAsync();
        await using var restartedSender = new EngineClient();
        var senderRestored = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        var senderCompleted = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        restartedSender.StateReceived += value =>
        {
            if (value["outgoing"] is not JsonObject outgoing || outgoing["files"] is not JsonArray entries ||
                !entries.Any(entry => entry?["fileName"]?.ToString() == "sender-restart-payload.bin")) return;
            if (value["ready"]?.GetValue<bool>() == true && outgoing["status"]?.ToString() == "paused") senderRestored.TrySetResult(outgoing);
            if (outgoing["status"]?.ToString() is "completed" or "failed" or "cancelled") senderCompleted.TrySetResult(outgoing);
        };
        restartedSender.Start(root);
        await senderRestored.Task.WaitAsync(TimeSpan.FromSeconds(20));
        if (senderCompleted.Task.IsCompleted) throw new Exception("Restored sender must wait for explicit continue");
        await restartedSender.CallAsync("transfer.continue", new { direction = "send" });
        var senderResult = await senderCompleted.Task.WaitAsync(TimeSpan.FromSeconds(40));
        if (senderResult["status"]?.ToString() != "completed") throw new Exception("Sender restart resume failed: " + senderResult.ToJsonString());
        await using var senderRecovered = File.OpenRead(Path.Combine(peerReceived, "sender-restart-payload.bin"));
        var senderHash = await SHA256.HashDataAsync(senderRecovered);
        if (!senderHash.SequenceEqual(originalHash)) throw new Exception("Sender restart SHA-256 mismatch");
        for (var attempt = 0; attempt < 20; attempt++)
        {
            history = await restartedPeer.CallAsync("history.list");
            if (history is JsonArray entries && entries.Count == 7) break;
            await Task.Delay(50);
        }
        if (history is not JsonArray senderHistory || senderHistory.Count != 7 ||
            senderHistory.Select(entry => entry?["id"]?.ToString()).Distinct().Count() != 7 ||
            Directory.GetFiles(peerReceived, "*", SearchOption.AllDirectories).Length != 7) throw new Exception("Sender restart duplicated files/history");
        // A changed prefix requires explicit per-file reset, preserving already published files.
        var resetSource = Path.Combine(root, "reset-source.bin"); File.Copy(restartFile, resetSource);
        var resetProgress = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        restartedSender.StateReceived += value => {
            if (value["outgoing"] is JsonObject outgoing && outgoing["status"]?.ToString() == "transferring" &&
                outgoing["transferredBytes"]?.GetValue<long>() >= 8L * 1024 * 1024 && outgoing["files"] is JsonArray entries &&
                entries.Any(entry => entry?["fileName"]?.ToString() == "reset-payload.bin")) resetProgress.TrySetResult();
        };
        await restartedSender.CallAsync("send.files", new { fingerprint = peerFingerprint, files = new[] {
            new { path = restartSmall, name = "reset-small.bin" }, new { path = resetSource, name = "reset-payload.bin" } } });
        await resetProgress.Task.WaitAsync(TimeSpan.FromSeconds(30)); await restartedSender.DisposeAsync();
        using (var changed = new FileStream(resetSource, FileMode.Open, FileAccess.ReadWrite)) {
            var firstByte = changed.ReadByte(); changed.Position = 0; changed.WriteByte((byte)(firstByte ^ 1));
        }
        await using var resetSender = new EngineClient();
        var resetReady = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var changedFile = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        var resetDone = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        resetSender.StateReceived += value => {
            if (value["outgoing"] is not JsonObject outgoing || outgoing["files"] is not JsonArray entries ||
                !entries.Any(entry => entry?["fileName"]?.ToString() == "reset-payload.bin")) return;
            if (value["ready"]?.GetValue<bool>() == true && outgoing["status"]?.ToString() == "paused") resetReady.TrySetResult();
            if (outgoing["status"]?.ToString() == "paused" && entries.OfType<JsonObject>().FirstOrDefault(file => file["restartRequired"]?.GetValue<bool>() == true) is JsonObject file)
                changedFile.TrySetResult(file);
            if (outgoing["status"]?.ToString() is "completed" or "failed" or "cancelled") resetDone.TrySetResult(outgoing);
        };
        resetSender.Start(root); await resetReady.Task.WaitAsync(TimeSpan.FromSeconds(20));
        await resetSender.CallAsync("transfer.continue", new { direction = "send" });
        var changedEntry = await changedFile.Task.WaitAsync(TimeSpan.FromSeconds(20));
        if (resetDone.Task.IsCompleted) throw new Exception("Changed source must remain paused until explicit reset");
        await resetSender.CallAsync("transfer.reset-file", new { direction = "send", fileId = changedEntry["id"]!.ToString() });
        var resetResult = await resetDone.Task.WaitAsync(TimeSpan.FromSeconds(40));
        if (resetResult["status"]?.ToString() != "completed") throw new Exception("Per-file reset failed: " + resetResult.ToJsonString());
        await using var changedOriginal = File.OpenRead(resetSource);
        await using var changedReceived = File.OpenRead(Path.Combine(peerReceived, "reset-payload.bin"));
        var changedSourceHash = await SHA256.HashDataAsync(changedOriginal);
        var changedReceivedHash = await SHA256.HashDataAsync(changedReceived);
        if (!changedSourceHash.SequenceEqual(changedReceivedHash)) throw new Exception("Per-file reset mixed old and new bytes");
        for (var attempt = 0; attempt < 20; attempt++) {
            history = await restartedPeer.CallAsync("history.list"); if (history is JsonArray entries && entries.Count == 9) break;
            await Task.Delay(50);
        }
        if (history is not JsonArray resetHistory || resetHistory.Count != 9 ||
            resetHistory.Select(entry => entry?["id"]?.ToString()).Distinct().Count() != 9 ||
            Directory.GetFiles(peerReceived, "*", SearchOption.AllDirectories).Length != 9) throw new Exception("Per-file reset duplicated files/history");
    }
    Console.WriteLine("PASS: concurrent IPC, UTF-8, share/bridge, unpaired two-process BP/2 TCP/TLS, receiver and sender restart/resume, explicit per-file reset, SHA-256, deduplicated files/history, shutdown.");
}
finally { Directory.Delete(root, recursive: true); }
