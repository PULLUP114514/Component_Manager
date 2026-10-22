using System.Text.Json;
using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public sealed record PhotoSyncState(string State = "pending", int Uploaded = 0, int Downloaded = 0, int Pending = 0, int Errors = 0, string? LastSuccess = null, string Detail = "", string[]? CompletedKeys = null, string? ServerId = null, string? AccountId = null, string? ServerUrl = null)
{
    public string Summary => $"照片：{(State == "complete" ? "完成" : State == "running" ? "上次传输中断，待重试" : State == "unsupported" ? "服务器暂不支持" : State == "partial" ? "部分完成，请重试" : "本地已保存，待同步")} · 已上传 {Uploaded} · 已下载 {Downloaded} · 待处理 {Pending} · 错误 {Errors}" + (LastSuccess is null ? "" : $" · 最近完成 {LastSuccess}") + (Detail.Length == 0 ? "" : " · " + Detail);
}

public sealed class PhotoSyncService(InventoryStore store, SyncApiClient api)
{
    public event Action? ProgressChanged;
    private string Root => Path.Combine(Path.GetDirectoryName(store.DatabasePath)!, "images");
    private string StatePath => Path.Combine(Root, "photo-sync-state.json");
    public PhotoSyncState ReadState()
    {
        try { return JsonSerializer.Deserialize<PhotoSyncState>(File.ReadAllText(StatePath)) ?? new(); }
        catch (Exception e) when (e is IOException or JsonException) { return new(); }
    }
    private void Save(PhotoSyncState state)
    {
        Directory.CreateDirectory(Root);
        File.WriteAllText(StatePath + ".tmp", JsonSerializer.Serialize(state));
        File.Move(StatePath + ".tmp", StatePath, true);
        ProgressChanged?.Invoke();
    }
    private HashSet<string> Keys() => store.GetComponents().Where(c => !c.Deleted).Select(c => PrivateProductImageStore.KeyFromDescription(c.Description)).OfType<string>().ToHashSet(StringComparer.Ordinal);
    public string Summary => CurrentState.Summary;
    public PhotoSyncState CurrentState
    {
        get
        {
            var state = ReadState();
            var binding = store.GetBoundSyncIdentity();
            if (state.ServerUrl != store.GetSyncConfiguration().ServerBaseUrl
                || state.ServerId != binding.ServerId || state.AccountId != binding.AccountId)
                return new PhotoSyncState(Pending: Keys().Count, Detail: "当前服务器或账户的照片尚未确认，请重新同步；本地照片已保留。");
            var completed = state.CompletedKeys ?? [];
            var missing = Keys().Count(k => !completed.Contains(k) || PrivateProductImageStore.Resolve(Root, PrivateProductImageStore.MarkerPrefix + k) is null);
            return missing > 0 && state.State == "complete" ? state with { State = "pending", Pending = missing } : state;
        }
    }
    public string ClearCache()
    {
        var cache = Path.Combine(Root, "downloaded");
        if (Directory.Exists(cache)) Directory.Delete(cache, true);
        Save(ReadState() with { State = "pending", Pending = Keys().Count(k => PrivateProductImageStore.Resolve(Root, PrivateProductImageStore.MarkerPrefix + k) is null), Detail = "下载缓存已清理，原始照片保留；请同步重新下载。" });
        return Summary;
    }
    public async Task<string> RunAsync(SyncConfiguration expected, SyncRunResult result, CancellationToken cancellation)
    {
        var old = CurrentState;
        if (old.ServerUrl != expected.ServerBaseUrl || old.ServerId != result.Identity?.ServerId || old.AccountId != result.Identity?.AccountId)
            old = new PhotoSyncState();
        var keys = Keys();
        var state = new PhotoSyncState("running", Pending: keys.Count, LastSuccess: old.LastSuccess,
            ServerId: result.Identity?.ServerId, AccountId: result.Identity?.AccountId, ServerUrl: expected.ServerBaseUrl);
        Save(state);
        try
        {
            cancellation.ThrowIfCancellationRequested();
            if (result.PhotoProtocol != 1) { state = state with { State = "unsupported", Detail = "本地照片保留，升级服务器后重试。" }; return state.Summary; }
            void CheckBinding()
            {
                var current = store.GetSyncConfiguration();
                if (!current.MatchesConnectionDraft(expected.ServerBaseUrl, expected.FallbackServerBaseUrl, expected.ApiToken, expected.AutoSyncEnabled)
                    || result.Identity is null || store.ValidateAndBindSyncIdentity(result.Identity, expected) is not null)
                    throw new InvalidOperationException("同步账户或连接已变化，请重试。");
            }
            CheckBinding();
            var manifest = await api.GetPhotosAsync(expected, result.UsedServerBaseUrl, result.Identity!.AccountId, cancellation);
            if (manifest.PhotoProtocol != 1 || manifest.Photos is null) throw new InvalidDataException("照片协议无效。");
            var remote = manifest.Photos.ToDictionary(p => p.Key, StringComparer.Ordinal);
            var cache = Path.Combine(Root, "downloaded");
            var incoming = Path.Combine(cache, ".incoming");
            if (Directory.Exists(incoming)) Directory.Delete(incoming, true);
            if (Directory.Exists(cache)) foreach (var file in Directory.EnumerateFiles(cache)) if (!keys.Contains(Path.GetFileName(file))) File.Delete(file);
            foreach (var key in keys)
            {
                cancellation.ThrowIfCancellationRequested(); CheckBinding();
                try
                {
                    if (!remote.TryGetValue(key, out var item)) throw new InvalidDataException("服务器尚未收到照片引用，请重试。");
                    var local = PrivateProductImageStore.Resolve(Root, PrivateProductImageStore.MarkerPrefix + key);
                    if (!item.Available && local is null) throw new FileNotFoundException("缺少原始照片，请在保存原图的设备同步。");
                    if (!item.Available || local is null)
                    {
                        await api.TransferPhotoAsync(expected, result.UsedServerBaseUrl, result.Identity.AccountId, key, item.Available ? null : local, cache, cancellation, CheckBinding);
                        CheckBinding();
                        state = item.Available ? state with { Downloaded = state.Downloaded + 1 } : state with { Uploaded = state.Uploaded + 1 };
                    }
                    state = state with { Pending = state.Pending - 1, CompletedKeys = [.. state.CompletedKeys ?? [], key] };
                }
                catch (Exception error) when (error is not OperationCanceledException) { state = state with { Errors = state.Errors + 1, Detail = error.Message }; }
                Save(state);
            }
            CheckBinding();
            state = state with { State = state.Errors == 0 ? "complete" : "partial", LastSuccess = state.Errors == 0 ? DateTimeOffset.Now.ToString("g") : old.LastSuccess };
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            state = state with { State = "pending", Detail = "照片同步已取消，已完成文件保留，请重试。" };
            throw;
        }
        catch (Exception error) { state = state with { State = "partial", Errors = state.Errors + 1, Detail = error.Message }; }
        finally { Save(state); }
        return state.Summary;
    }
}
