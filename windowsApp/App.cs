using Microsoft.UI.Reactor;
using Microsoft.UI.Reactor.Animation;
using Microsoft.UI.Reactor.Core;
using Microsoft.UI.Reactor.Layout;
using Microsoft.UI.Reactor.Hosting;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Boxlet.Windows;
using System.Text.Json.Nodes;
using static Microsoft.UI.Reactor.Factories;

using var instance = new Mutex(true, "Local\\Boxlet.Windows", out var first);
using var activation = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\Boxlet.Windows.Activate");
using var shutdown = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\Boxlet.Windows.Shutdown");
if (!first) { if (args.Contains("--exit")) shutdown.Set(); else activation.Set(); return; }
if (args.Contains("--exit")) return;
ReactorApp.AppLogger = new ClientLogger();
try
{
    ReactorApp.Run<BoxletApp>(new WindowSpec {
        Title = "Boxlet", Width = 1180, Height = 780, MinWidth = 920, MinHeight = 640,
        CornerStyle = WindowCornerStyle.Rounded, Backdrop = BackdropChoice.Of(BackdropKind.Mica),
        Icon = WindowIcon.FromPath(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico")),
    });
}
finally { Lifetime.Model?.StopAsync().GetAwaiter().GetResult(); }
static class Lifetime { public static AppController? Model; }

sealed class ClientLogger : Microsoft.Extensions.Logging.ILogger
{
    readonly string path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Boxlet", "windows-client.log");
    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
    public bool IsEnabled(Microsoft.Extensions.Logging.LogLevel level) => level >= Microsoft.Extensions.Logging.LogLevel.Warning;
    public void Log<TState>(Microsoft.Extensions.Logging.LogLevel level, Microsoft.Extensions.Logging.EventId id, TState state, Exception? error, Func<TState, Exception?, string> formatter) {
        if (!IsEnabled(level)) return;
        try { Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.AppendAllText(path, $"{DateTimeOffset.Now:O} {level} {formatter(state, error)} {error}\n"); } catch { }
    }
}

class BoxletApp : Component
{
    public override Element Render()
    {
        var m = UseMemo(() => new AppController());
        UseExternalStore(m.Subscribe, () => m.Version);
        var (page, setPage) = UseState("send");
        var (pane, setPane) = UseState(true);
        var (textOpen, setTextOpen) = UseState(false);
        var (textDraft, setTextDraft) = UseState("");
        var (manageOpen, setManageOpen) = UseState(false);
        var window = UseWindow();
        var tray = UseTrayIcon(new TrayIconSpec(WindowIcon.FromPath(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico")), "Boxlet · 局域网文件互传"));
        var pickFolder = UseFolderPickerAsync;
        UseEffect(() => { Lifetime.Model = m; _ = m.StartAsync(); });
        UseEffect(() => {
            if (window is null || tray is null) return () => { };
            void Show() { window.Show(); window.Activate(); }
            void Click(object? sender, EventArgs args) => Show();
            async void Exit() { tray.HideFlyout(); await m.StopAsync(); ReactorApp.Exit(); }
            void Menu(object? sender, EventArgs args) => tray.ShowFlyout(VStack(8, Button("显示主窗口", Show), Button("退出 Boxlet", Exit)).Padding(12));
            void Closing(object? sender, Microsoft.UI.Reactor.WindowClosingEventArgs args) { args.Cancel = true; window.Hide(); }
            window.Closing += Closing; tray.Click += Click; tray.DoubleClick += Click; tray.RightClick += Menu;
            var queue = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
            var cancellation = new CancellationTokenSource();
            _ = Task.Run(() => {
                using var signal = EventWaitHandle.OpenExisting("Local\\Boxlet.Windows.Activate");
                using var stop = EventWaitHandle.OpenExisting("Local\\Boxlet.Windows.Shutdown");
                while (true) {
                    var result = WaitHandle.WaitAny([signal, stop, cancellation.Token.WaitHandle]);
                    if (result == 0) queue.TryEnqueue(Show);
                    else { if (result == 1) queue.TryEnqueue(Exit); break; }
                }
            });
            return () => { cancellation.Cancel(); window.Closing -= Closing; tray.Click -= Click; tray.DoubleClick -= Click; tray.RightClick -= Menu; };
        }, window, tray);
        void Run(Func<Task> action) => _ = m.RunAsync(action);
        async Task Files(string[]? filter = null) {
            if (window is null) return;
            var picker = new Windows.Storage.Pickers.FileOpenPicker();
            foreach (var extension in filter ?? ["*"]) picker.FileTypeFilter.Add(extension);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(window.NativeWindow));
            var files = await picker.PickMultipleFilesAsync();
            if (files.Count > 0) await m.ImportAsync(files.Select(file => file.Path));
        }
        async Task Folder(bool save = false) {
            var folder = await pickFolder(new FolderPickerOptions());
            if (folder is null) return;
            if (save) await m.SaveAsync("destination", folder.Path); else await m.ImportAsync([folder.Path]);
        }
        Task Text() { setTextOpen(true); return Task.CompletedTask; }
        Task Manage() { setManageOpen(true); return Task.CompletedTask; }
        Element content = page switch {
            "receive" => Receive(m, Run), "history" => History(m, Run),
            NavigationViewElement.SettingsTag => Settings(m, Run, () => Folder(true)),
            _ => Send(m, Run, () => Files(), () => Folder(), Text, Manage),
        };
        var children = new List<Element>();
        if (m.State["pendingReceive"] is JsonObject pending) {
            var pendingFiles = pending["files"]?.AsArray() ?? new JsonArray();
            var isMessage = pending["isMessage"]?.GetValue<bool>() == true;
            var headline = isMessage
                ? pending["message"]?.ToString() ?? "文本消息"
                : $"{pendingFiles.Count} 个文件 · {AppController.Bytes(pendingFiles.Sum(file => file?["size"]?.GetValue<long>() ?? 0))}";
            var destination = m.Settings["destination"]?.ToString() ?? AppController.DefaultDestination;
            children.Add(Ui.Panel(VStack(16,
                    // 三行、三个层级：来源 / 内容规模 / 落点；按钮单独占一行，接收在右。
                    VStack(4,
                        Caption($"来自 {pending["peer"]} 的传输请求").Foreground(Theme.SecondaryText),
                        Subtitle(headline).Set(text => { text.TextWrapping = TextWrapping.Wrap; text.MaxLines = 2; }),
                        Caption(isMessage ? "文本消息" : $"保存到 {destination}").Foreground(Theme.TertiaryText)),
                    Ui.Columns(12,
                        Button("拒绝", () => Run(() => m.CommandAsync("receive.reject")))
                            .MinHeight(40).HAlign(HorizontalAlignment.Stretch).HorizontalContentAlignment(HorizontalAlignment.Center),
                        Button(Ui.ButtonLabel("接收", "ArrowDownload"), () => Run(() => m.CommandAsync("receive.accept")))
                            .AutomationName("接收传输").AccentButton().MinHeight(40).HAlign(HorizontalAlignment.Stretch).HorizontalContentAlignment(HorizontalAlignment.Center))))
                .Transition(Transition.Slide(Edge.Bottom) + Transition.Scale(0.96f)));
        }
        if (m.State["outgoing"]?["status"]?.ToString() == "pin_required") children.Add(Ui.Panel(VStack(12,
            BodyStrong("需要 PIN"), TextBox(m.Pin, value => { m.Pin = value; m.Notify(); }, placeholderText: "PIN").AutomationName("接收 PIN"),
            HStack(8, Button("提交", () => Run(() => m.CommandAsync("transfer.pin", new { pin = m.Pin }))), Button("取消", () => Run(() => m.CommandAsync("transfer.cancel")))))));
        children.Add(content);
        var scroll = ScrollViewer(VStack(16, children.ToArray()).Padding(24)).HAlign(HorizontalAlignment.Stretch).VAlign(VerticalAlignment.Stretch)
            .HorizontalContentAlignment(HorizontalAlignment.Stretch).Background(new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent)).Set(control => {
            control.HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled; control.HorizontalScrollMode = ScrollMode.Disabled;
            control.AllowDrop = page == "send";
        }).OnMountAdd(control => Ui.AttachFileDrop(control, m));
        var nav = (NavigationView([NavItem("发送", tag: "send") with { IconElement = BrandIcons.Get("Send") }, NavItem("接收", tag: "receive") with { IconElement = BrandIcons.Get("ArrowDownload") }, NavItem("历史", tag: "history") with { IconElement = BrandIcons.Get("History") }], scroll) with {
            SelectedTag = page, IsSettingsVisible = true, IsPaneToggleButtonVisible = false, IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed,
            // 切页时清掉上一页的操作提示，避免旧提示跟着跑到别的页面。
            OnSelectedTagChanged = tag => { setPage(tag ?? NavigationViewElement.SettingsTag); m.Message(""); m.ClearSendHint(); if (m.Connected && tag == "history") Run(m.RefreshListsAsync); },
        }).IsPaneOpen(pane, setPane).Set(control => {
            control.OpenPaneLength = 240;
            control.HorizontalContentAlignment = HorizontalAlignment.Stretch;
            control.VerticalContentAlignment = VerticalAlignment.Stretch;
        }).Flex(grow: 1, basis: 0);
        var shell = FlexColumn(TitleBar("Boxlet").Icon("ms-appx:///Assets/BoxletMark.png").PaneToggleButtonVisible(true).PaneToggleRequested(() => setPane(!pane)).Flex(shrink: 0), nav)
            .Backdrop(BackdropKind.Mica).Set(panel => panel.RequestedTheme = m.Settings["theme"]?.ToString() switch { "LIGHT" => ElementTheme.Light, "DARK" => ElementTheme.Dark, _ => ElementTheme.Default });
        var textDialog = ContentDialog("添加文本", TextBox(textDraft, setTextDraft, placeholderText: "输入要发送的文本").AutomationName("发送文本").Height(180).Set(control => { control.AcceptsReturn = true; control.TextWrapping = TextWrapping.Wrap; }), "添加") with {
                IsOpen = textOpen, CloseButtonText = "取消", IsPrimaryButtonEnabled = !string.IsNullOrWhiteSpace(textDraft),
                OnClosed = result => { setTextOpen(false); if (result == ContentDialogResult.Primary) { m.SelectText(textDraft); setTextDraft(""); } },
            };
        var manageDialog = ContentDialog("已打包的内容", ScrollViewer(m.SelectedText is not null ? Ui.Wrap(m.SelectedText) : VStack(8, m.Files.Select(file => (Element)FlexRow(Ui.Wrap(file.Name).Flex(grow: 1, basis: 0), Button("移除", () => { m.Files.Remove(file); m.Notify(); })).Gap(12)).ToArray())).Height(320).Width(420), "完成") with {
                IsOpen = manageOpen, OnClosed = _ => setManageOpen(false),
            };
        return shell with { Children = [..shell.Children, textDialog, manageDialog] };
    }

    static Element Send(AppController m, Action<Func<Task>> run, Func<Task> files, Func<Task> folder, Func<Task> text, Func<Task> manage)
    {
        var devices = m.State["devices"] as JsonArray ?? new();
        var count = m.Files.Count + (m.SelectedText is null ? 0 : 1);
        var title = count == 0 ? "选择要发送的内容" : $"已打包 {count} 项内容";
        var size = m.SelectedText is not null ? System.Text.Encoding.UTF8.GetByteCount(m.SelectedText) : m.Files.Sum(f => f.Size);
        var selection = (FlexRow(Ui.Parcel(count), VStack(4, BodyStrong(title), Caption(count == 0
                ? "先选择内容或拖入文件，再选择设备发送" : $"{AppController.Bytes(size)} · 选择下方设备即可发送").Foreground(Theme.SecondaryText).Set(control => control.TextWrapping = TextWrapping.Wrap)).Flex(grow: 1, basis: 0),
            count > 0 ? HStack(8, Button("查看内容", () => run(manage)), Button("清空", m.Clear)) : Empty()).Gap(16) with { AlignItems = FlexAlign.Center });
        var items = new List<Element> {
            Ui.Header("发送", $"本机 {m.Text("alias", "Boxlet")} · {(m.State["serverRunning"]?.GetValue<bool>() == true ? "可在局域网内被发现" : "接收服务未运行")}", HStack(8,
                Button(Ui.ButtonLabel("浏览器分享", "Globe"), () => run(m.ShareAsync)).AutomationName("浏览器分享").IsEnabled(m.Connected && !m.Busy), Button(Ui.ButtonLabel("扫描设备", "ArrowSync"), () => run(() => m.CommandAsync("devices.scan"))).AutomationName("扫描设备").AccentButton().IsEnabled(m.Connected && !m.Busy))),
            Ui.Panel(VStack(16, selection,
                Ui.ActionRow(Ui.Action("文件", "Document", () => run(files)), Ui.Action("文件夹", "Folder", () => run(folder)),
                    Ui.Action("文本", "TextDescription", () => run(text)), Ui.Action("剪贴板", "ClipboardPaste", () => run(m.ClipboardAsync))))),
        };
        if (m.State["share"] is JsonObject share) items.Add(Ui.Panel(VStack(12, BodyStrong("浏览器分享已开启"), Ui.Wrap(share["url"]?.ToString() ?? ""), HStack(8,
            Button("复制链接", () => AppController.Copy(share["url"]?.ToString() ?? "")), Button("停止分享", () => run(() => m.CommandAsync("share.stop")))))));
        if (m.State["outgoing"] is JsonObject transfer) items.Add(Transfer(m, transfer, "send", run));
        items.Add(BodyStrong($"附近设备{(devices.Count > 0 ? $" · {devices.Count}" : "")}").Margin(0, 8, 0, 0));
        // 提示贴在设备区，不再占页面顶部。
        if (m.Notice.Length > 0) items.Add(Ui.Notice(m.Notice));
        if (devices.Count == 0) items.Add(Ui.Panel(VStack(8, BodyStrong("还没有发现设备"), Ui.Wrap("请确认对方已打开 Boxlet 或 LocalSend，并且两台设备连接到同一 Wi-Fi。"), Caption("如果设备没有自动出现，可点击右上角「扫描设备」。"))));
        // The card body also sends; the explicit 发送 button stays for discoverability.
        var ready = m.Files.Count > 0 || m.SelectedText is not null;
        foreach (var device in devices.OfType<JsonObject>()) {
            var fingerprint = device["fingerprint"]!.ToString();
            var alias = device["alias"]?.ToString() ?? "设备";
            var favorite = m.Favorites.Any(f => f?["fingerprint"]?.ToString() == fingerprint);
            var model = device["deviceModel"]?.ToString() ?? device["deviceType"]?.ToString() ?? "";
            var online = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - (device["lastSeen"]?.GetValue<long>() ?? 0L) < 20_000;
            var details = string.Join(" · ", new[] { model, online ? "在线" : "最近可见" }.Where(part => part.Length > 0));
            items.Add(Card(Grid([GridSize.Star(), GridSize.Auto, GridSize.Auto], [GridSize.Auto],
                Button(FlexRow(
                        Border(Ui.BrandIcon("Send", 20).HAlign(HorizontalAlignment.Center).VAlign(VerticalAlignment.Center))
                            .Background(Theme.AccentSecondary).CornerRadius(20).Width(40).Height(40).Flex(shrink: 0),
                        VStack(4, BodyStrong(alias), Caption(details).Foreground(Theme.SecondaryText)).Flex(grow: 1, basis: 0),
                        // 只有空内容点了设备后才提示，平时不占位置。
                        !ready && m.SendHint ? Caption("请先选择要发送的内容").Foreground(Theme.SystemCaution) : Empty()).Gap(12),
                    () => run(() => m.SendAsync(fingerprint)))
                    .SubtleButton().AutomationName($"发送到 {alias}").IsEnabled(m.Connected && !m.Busy)
                    .HAlign(HorizontalAlignment.Stretch).HorizontalContentAlignment(HorizontalAlignment.Stretch).Padding(8, 4)
                    .Grid(column: 0).WithKey("card"),
                Button(favorite ? "★ 已收藏" : "☆ 收藏", () => run(() => m.ToggleFavoriteAsync(device))).AutomationName(favorite ? "取消收藏" : "收藏设备")
                    .VAlign(VerticalAlignment.Center).Grid(column: 1).WithKey("favorite"),
                Button("发送", () => run(() => m.SendAsync(fingerprint))).AutomationName($"发送到 {alias}").IsEnabled(m.Connected && !m.Busy)
                    .VAlign(VerticalAlignment.Center).Grid(column: 2).WithKey("send")) with { ColumnSpacing = 8 }).Padding(8).CornerRadius(Ui.Radius()));
        }
        return VStack(16, items.ToArray());
    }
    static Element Receive(AppController m, Action<Func<Task>> run)
    {
        var items = new List<Element> { Ui.Header("接收", m.State["serverRunning"]?.GetValue<bool>() == true ? $"服务运行中 · 端口 {m.Text("port")}" : "服务未运行"),
            Ui.Panel(VStack(12, BodyStrong("本机信息"), Ui.Detail("设备名称", m.Text("alias")), Ui.Detail("监听端口", m.Text("port")), Ui.Detail("传输加密", m.Flag("https") ? "HTTPS · 指纹固定" : "HTTP"),
                Ui.Detail("保存目录", m.Settings["destination"]?.ToString() ?? AppController.DefaultDestination), Ui.Detail("接收 PIN", string.IsNullOrEmpty(m.Settings["receivePin"]?.ToString()) ? "未设置" : "已设置"))),
            Ui.Panel(VStack(16, BodyStrong("自动接收"), Ui.Switch("快速保存", "自动接受所有传输请求", m.Flag("quickSave"), value => run(() => m.SaveAsync("quickSave", value))),
                Ui.Switch("仅收藏设备", "只自动接受已收藏设备", m.Flag("quickSaveFromFavorites"), value => run(() => m.SaveAsync("quickSaveFromFavorites", value))))) };
        if (m.Notice.Length > 0) items.Insert(1, Ui.Notice(m.Notice));
        if (m.State["incoming"] is JsonObject transfer) items.Add(Transfer(m, transfer, "receive", run));
        return VStack(16, items.ToArray());
    }
    static Element Transfer(AppController m, JsonObject t, string direction, Action<Func<Task>> run)
    {
        var status = t["status"]?.ToString() ?? "";
        var active = status is "transferring" or "preparing" or "waiting_for_accept" or "pin_required";
        var label = status switch { "transferring" => "正在传输", "preparing" => "正在准备", "waiting_for_accept" => "等待对方接收", "completed" => "已完成", "cancelled" => "已取消", "failed" => "失败", "declined" => "对方拒绝", "pin_required" => "需要 PIN", _ => status };
        return Ui.Panel(VStack(12, BodyStrong($"{(direction == "send" ? "发送到" : "来自")} {t["peer"]}"), Body(label), Progress((t["progress"]?.GetValue<double>() ?? 0) * 100),
            Caption($"{AppController.Bytes(t["transferredBytes"]?.GetValue<long>() ?? 0)} / {AppController.Bytes(t["totalBytes"]?.GetValue<long>() ?? 0)}"),
            string.IsNullOrEmpty(t["error"]?.ToString()) ? Empty() : Ui.Wrap(t["error"]!.ToString()), active ? Button("取消传输", () => run(() => m.CommandAsync("transfer.cancel", new { direction }))) : Empty()));
    }
    static Element History(AppController m, Action<Func<Task>> run)
    {
        var saveFolder = m.Settings["destination"]?.ToString() ?? AppController.DefaultDestination;
        void Do(Action action) => run(() => { action(); return Task.CompletedTask; });
        var items = new List<Element> { Ui.Header("历史", "最近接收的文件与消息", HStack(8,
            Button(Ui.ButtonLabel("打开保存文件夹", "Folder"), () => Do(() => AppController.OpenFolder(saveFolder))).AutomationName("打开保存文件夹"),
            Button("清空历史", () => run(async () => { await m.CommandAsync("history.clear"); await m.RefreshListsAsync(); })).IsEnabled(m.Connected && m.History.Count > 0))) };
        if (m.Notice.Length > 0) items.Add(Ui.Notice(m.Notice));
        if (m.History.Count == 0) items.Add(Ui.Panel(VStack(8, BodyStrong("还没有接收记录"), Caption("收到的文件和消息会显示在这里。"))));
        foreach (var entry in m.History.OfType<JsonObject>()) {
            var path = entry["path"]?.ToString();
            var isMessage = entry["isMessage"]?.GetValue<bool>() == true;
            var name = entry["fileName"]?.ToString() ?? "";
            var fileType = entry["fileType"]?.ToString() ?? "";
            var directory = string.IsNullOrEmpty(path) ? null : Path.GetDirectoryName(path);
            var time = DateTimeOffset.FromUnixTimeMilliseconds(entry["timestamp"]?.GetValue<long>() ?? 0).LocalDateTime.ToString("MM-dd HH:mm");
            var icon = isMessage || fileType.StartsWith("text/") ? "TextDescription" : fileType.StartsWith("image/") ? "Image" : "Document";
            var actions = new List<Element>();
            if (!string.IsNullOrEmpty(path)) {
                actions.Add(Button(Ui.ButtonLabel("打开文件", "Document"), () => Do(() => AppController.OpenFile(path!))).AutomationName($"打开 {name}"));
                actions.Add(Button(Ui.ButtonLabel("打开文件夹", "Folder"), () => Do(() => AppController.RevealInExplorer(path!))).AutomationName("打开所在文件夹"));
            }
            items.Add(Card(Grid([GridSize.Auto, GridSize.Star(), GridSize.Auto], [GridSize.Auto],
                Border(Ui.BrandIcon(icon, 20).HAlign(HorizontalAlignment.Center).VAlign(VerticalAlignment.Center))
                    .Background(Theme.AccentSecondary).CornerRadius(Ui.Radius()).Width(40).Height(40).Grid(column: 0),
                VStack(4,
                    BodyStrong(name).Set(text => { text.TextWrapping = TextWrapping.NoWrap; text.TextTrimming = TextTrimming.CharacterEllipsis; }),
                    Caption($"{entry["senderAlias"]} · {time} · {AppController.Bytes(entry["fileSize"]?.GetValue<long>() ?? 0)}").Foreground(Theme.SecondaryText),
                    directory is null ? Empty() : Caption(directory).Foreground(Theme.TertiaryText).Set(text => { text.TextWrapping = TextWrapping.NoWrap; text.TextTrimming = TextTrimming.CharacterEllipsis; })
                ).Flex(grow: 1, basis: 0).Grid(column: 1),
                HStack(8, actions.ToArray()).VAlign(VerticalAlignment.Center).Grid(column: 2)) with { ColumnSpacing = 12 }).Padding(16, 8).CornerRadius(Ui.Radius()));
        }
        return VStack(12, items.ToArray());
    }
    static Element Settings(AppController m, Action<Func<Task>> run, Func<Task> destination)
    {
        Element Commit(string label, string key, bool number = false) => VStack(8, Body(label), HStack(8, TextBox(m.Draft(key), value => m.Edit(key, value)).AutomationName(label).Width(300),
            Button("应用", () => run(async () => {
                var text = m.Draft(key).Trim();
                if (number) { if (!int.TryParse(text, out var port) || port < 1024 || port > 65535) throw new InvalidOperationException("端口必须在 1024–65535 之间。"); await m.SaveAsync(key, port); }
                else if (key is "networkWhitelist" or "networkBlacklist") await m.SaveAsync(key, text.Split([',', ';', ' '], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
                else { if (key is "alias" or "multicastGroup" && text.Length == 0) throw new InvalidOperationException("此字段不能为空。"); await m.SaveAsync(key, text.Length == 0 ? null : text); }
            })).IsEnabled(m.Connected && !m.Busy && m.Draft(key) != m.SettingText(key))));
        Element Switch(string title, string description, string key) => Ui.Switch(title, description, m.Flag(key), value => run(() => m.SaveAsync(key, value)));
        var sections = new List<Element> { Ui.Header("设置", "修改会立即生效并保存到本机"),
            Ui.Panel(VStack(16, BodyStrong("设备"), Commit("设备名称", "alias"), Commit("监听端口", "port", true), Commit("设备型号", "deviceModel"))),
            Ui.Panel(VStack(16, BodyStrong("传输"), Switch("快速保存", "自动接受所有传输请求", "quickSave"), Switch("仅收藏设备自动接收", "只对已收藏设备启用快速保存", "quickSaveFromFavorites"),
                Switch("发送时计算 SHA-256", "新版 Sendee 边传边校验；兼容设备提前计算", "createChecksums"), Switch("接收时校验 SHA-256", "校验失败时自动重试", "verifyChecksums"), Switch("记录接收历史", "保存收到的文件和消息记录", "saveToHistory"), Commit("接收 PIN（留空关闭）", "receivePin"))),
            Ui.Panel(VStack(12, BodyStrong("保存目录"), Ui.Wrap(m.Settings["destination"]?.ToString() ?? AppController.DefaultDestination), HStack(8,
                Button("选择目录", () => run(destination)).IsEnabled(m.Connected), Button("恢复默认", () => run(() => m.SaveAsync("destination", null))).IsEnabled(m.Connected)))),
            Ui.Panel(VStack(16, BodyStrong("网络"), Switch("HTTPS 加密", "自签名证书与设备指纹校验", "https"), Commit("多播组", "multicastGroup"),
                Commit("接口白名单（逗号分隔，支持 *）", "networkWhitelist"), Commit("接口黑名单（优先于白名单）", "networkBlacklist"))),
            Ui.Panel(VStack(12, BodyStrong("浏览器接入"), m.State["bridge"] is JsonObject bridge ? VStack(12, Ui.Wrap(bridge["url"]?.ToString() ?? ""), HStack(8,
                Button("复制地址", () => AppController.Copy(bridge["url"]?.ToString() ?? "")), Button("停止接入", () => run(() => m.CommandAsync("bridge.stop"))))) : Button("开启接入", () => run(() => m.CommandAsync("bridge.start"))).IsEnabled(m.Connected))),
            Ui.Panel(VStack(12, BodyStrong("外观"), ComboBox(["跟随系统", "浅色", "深色"], m.Settings["theme"]?.ToString() switch { "LIGHT" => 1, "DARK" => 2, _ => 0 },
                index => { var theme = new[] { "SYSTEM", "LIGHT", "DARK" }[Math.Clamp(index, 0, 2)]; if (theme != m.Settings["theme"]?.ToString()) run(() => m.SaveAsync("theme", theme)); }).Width(180))),
            Ui.Panel(VStack(12, HStack(12, Image("ms-appx:///Assets/BoxletMark.png").AutomationName("Boxlet 包裹图标").Width(48).Height(48), VStack(4, BodyStrong("Boxlet"), Caption("让传输像打包一样简单").Foreground(Theme.SecondaryText))), Body("1.0.0 · Windows 原生客户端"), Caption("兼容 LocalSend v2.2 协议").Foreground(Theme.SecondaryText), Ui.Wrap(m.Text("fingerprint")))) };
        if (m.Notice.Length > 0) sections.Insert(1, Ui.Notice(m.Notice));
        return VStack(16, sections.ToArray());
    }
}
static class Ui
{
    public static FlexElement Gap(this FlexElement element, double gap) => element with { ColumnGap = gap, RowGap = gap };
    // WinUI 内置圆角：控件 4px（ControlCornerRadius）、卡片与浮层 8px（OverlayCornerRadius）。
    public static double Radius(bool overlay = true) => ThemeResource.CornerRadius(overlay ? "OverlayCornerRadius" : "ControlCornerRadius").TopLeft;
    public static Element Panel(Element child) => Card(child.Padding(16)).CornerRadius(Radius());
    public static Element Wrap(string text) => TextBlock(text).Set(control => control.TextWrapping = TextWrapping.Wrap);
    public static Element Header(string title, string subtitle, Element? actions = null) => (FlexRow(VStack(4, Title(title), Caption(subtitle).Foreground(Theme.SecondaryText)).Flex(grow: 1, basis: 0), actions ?? Empty()).Gap(16) with { AlignItems = FlexAlign.Center }).Margin(0, 0, 0, 8);
    public static Element Detail(string label, string value) => FlexRow(Body(label).Width(100), Wrap(value).Flex(grow: 1, basis: 0)).Gap(16);
    public static Element Switch(string title, string description, bool value, Action<bool> changed) => FlexRow(VStack(4, Body(title), Caption(description)).Flex(grow: 1, basis: 0), ToggleSwitch(value, changed)).Gap(16);
    public static Element Parcel(int count) => new XamlHostElement(() => new ParcelView(), view => ((ParcelView)view).SetItemCount(count)) { TypeKey = "boxlet-parcel" }.Width(64).Height(64).Flex(shrink: 0);
    public static Element ButtonLabel(string label, string icon) => HStack(8, BrandIcon(icon, 16), Body(label));
    // 轻量提示条：不再占用页面顶部，调用方放到相关区块旁边。
    public static Element Notice(string text) => Card(FlexRow(BrandIcon("Info", 16), Wrap(text).Flex(grow: 1, basis: 0)).Gap(8).FlexPadding(12, 8)).CornerRadius(Radius());
    // Fluent vector paths are authored on a 24x24 grid and a bare PathIcon is clipped
    // rather than scaled, so mount the glyph at 24 and let a Viewbox fit the slot.
    public static Element BrandIcon(string name, double size) => Viewbox(Icon(BrandIcons.Get(name)).Width(24).Height(24))
        .Width(size).Height(size).Flex(shrink: 0);
    // Native star columns allocate identical widths, including at fractional DPI scales.
    public static Element Columns(double spacing, params Element[] children) => (Grid(
        Enumerable.Repeat(GridSize.Star(), children.Length).ToArray(), [GridSize.Auto],
        children.Select((child, index) => child.Grid(column: index).WithKey(index.ToString())).ToArray()) with { ColumnSpacing = spacing }).HAlign(HorizontalAlignment.Stretch);
    public static Element ActionRow(params Element[] actions) => Columns(8, actions);
    // 高度靠 padding 撑出来（WinUI 不建议给按钮写死 Height）；图标 24 是标准尺寸。
    public static Element Action(string label, string icon, Action action) => Button(VStack(8, BrandIcon(icon, 24).HAlign(HorizontalAlignment.Center), Body(label).HAlign(HorizontalAlignment.Center)), action).AutomationName(label).MinWidth(0).Padding(12, 12).HAlign(HorizontalAlignment.Stretch).CornerRadius(Radius(false)).HorizontalContentAlignment(HorizontalAlignment.Center);
    public static void AttachFileDrop(FrameworkElement control, AppController m)
    {
        if (control.Tag as string == "boxlet-drop") return;
        control.Tag = "boxlet-drop";
        // Listen on the entire viewport, including blank space and handled child events.
        control.AddHandler(UIElement.DragOverEvent, new DragEventHandler((_, args) => {
            if (!control.AllowDrop || !args.DataView.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems)) return;
            args.AcceptedOperation = Windows.ApplicationModel.DataTransfer.DataPackageOperation.Copy;
            args.Handled = true;
        }), true);
        control.AddHandler(UIElement.DropEvent, new DragEventHandler(async (_, args) => {
            if (!control.AllowDrop || !args.DataView.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems)) return;
            args.Handled = true;
            var deferral = args.GetDeferral();
            try {
                var dropped = await args.DataView.GetStorageItemsAsync();
                await m.RunAsync(() => m.ImportAsync(dropped.Select(item => item.Path)));
            } catch (Exception e) { m.Message(e.Message); }
            finally { deferral.Complete(); }
        }), true);
    }
}
