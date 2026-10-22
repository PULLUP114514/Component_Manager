using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.Services.SyncPersistence;
using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public sealed class InventorySyncService
{
    private readonly InventoryStore _store;
    private readonly SyncApiClient _apiClient;
    private readonly SyncRunStateStore _states;
    private BomPresetStore Presets => new(Path.Combine(Path.GetDirectoryName(_store.DatabasePath)!, "bom-presets.json"));
    public event Action? ProgressChanged;
    public InventorySyncService(InventoryStore store, SyncApiClient apiClient)
    {
        _store = store; _apiClient = apiClient; _states = new(store.DatabasePath);
    }
    public void RecoverInterrupted() => _states.RecoverInterrupted();
    private SyncRunState CurrentState()
    {
        var state = _states.Read();
        var binding = _store.GetBoundSyncIdentity();
        return state.ServerUrl == _store.GetSyncConfiguration().ServerBaseUrl
            && (state.ServerId ?? "") == binding.ServerId && (state.AccountId ?? "") == binding.AccountId
            ? state : new();
    }
    public string StatusSummary
    {
        get
        {
            var settings = _store.GetSyncConfiguration(); var state = CurrentState();
            var pending = _store.GetPendingSyncCount();
            string presets; var pendingPresetCount = 0;
            try { pendingPresetCount = SyncRunStateStore.PendingPresets(Presets.Snapshot(), state); presets = $"BOM 预设待同步 {pendingPresetCount}"; }
            catch (Exception error) { presets = error.Message; }
            var label = string.IsNullOrWhiteSpace(settings.ServerBaseUrl) || string.IsNullOrWhiteSpace(settings.ApiToken) ? "未配置同步，本地数据已保存"
                : state.State switch { "Running" => "正在同步", "Interrupted" => "上次同步中断，请重试", "Cancelled" => "同步已取消，可重试", "Complete" => pending > 0 || pendingPresetCount > 0 || new PhotoSyncService(_store, _apiClient).CurrentState.State != "complete" ? "本地已保存，待同步" : "上轮同步完成", "Partial" => "部分完成，请重试", "Failed" => "同步失败，请检查连接或提示后重试", _ => "本地已保存，待同步" };
            return $"{label} · 库存待同步 {pending} · {presets}" + (state.Detail.Length == 0 ? "" : " · 最近结果：" + state.Detail);
        }
    }
    public string InventoryLastSuccess => CurrentState().InventorySuccess ?? "尚未记录当前绑定的完成时间";
    public string ScopeSummary
    {
        get
        {
            var state = CurrentState();
            return $"库存最近完成：{InventoryLastSuccess} · BOM 预设最近完成：{state.PresetSuccess ?? "尚未完成"} · 整轮最近完成：{state.CompleteSuccess ?? "尚未完成"}";
        }
    }
    public string PhotoSummary
    {
        get
        {
            var summary = new PhotoSyncService(_store, _apiClient).Summary;
            return CurrentState().State == "Running" ? summary.Replace("上次传输中断，待重试", "正在传输") : summary;
        }
    }
    public string ClearPhotoCache() => new PhotoSyncService(_store, _apiClient).ClearCache();
    public Task<OperationResult> TestConnectionAsync(CancellationToken cancellationToken = default) => _apiClient.TestConnectionAsync(_store.GetSyncConfiguration(), cancellationToken);
    public Task<OperationResult> TestConnectionAsync(SyncConfiguration draft, CancellationToken cancellationToken = default) => _apiClient.TestConnectionAsync(draft, cancellationToken);
    private void Persist(SyncRunState state)
    {
        _states.Save(state);
        _store.UpdateSyncStatus(StatusSummary);
        ProgressChanged?.Invoke();
    }
    public async Task<SyncRunResult> RunSyncAsync(CancellationToken cancellationToken = default)
    {
        var envelope = _store.CreateSyncEnvelope();
        if (string.IsNullOrWhiteSpace(envelope.Settings.ServerBaseUrl) || string.IsNullOrWhiteSpace(envelope.Settings.ApiToken))
            return SyncRunResult.Failure("未配置同步；数据已保存在本机，填写服务器地址和令牌后可同步。");
        var binding = _store.GetBoundSyncIdentity();
        var state = CurrentState() with { State = "Running", Inventory = "Running", Presets = "Pending", Detail = "", ServerUrl = envelope.Settings.ServerBaseUrl,
            ServerId = binding.ServerId, AccountId = binding.AccountId };
        Persist(state);
        SyncRunResult? result = null;
        var inventoryDone = false;
        try
        {
            result = await _apiClient.RunSyncAsync(envelope.Settings, envelope.PushRequest, envelope.Cursor, cancellationToken,
                identity => _store.ValidateAndBindSyncIdentity(identity, envelope.Settings));
            if (!result.IsSuccess)
            {
                Persist(state with { State = "Failed", Inventory = "Failed", Detail = result.Message });
                return result;
            }
            if (!_store.ApplySyncResult(result, envelope.QueuedEntities, envelope.Settings.ServerBaseUrl, envelope.Settings))
                throw new InvalidOperationException("同步期间服务器配置已变化，本次旧服务器结果未应用。");
            inventoryDone = true;
            if (state.ServerId != result.Identity?.ServerId || state.AccountId != result.Identity?.AccountId)
                state = state with { PresetSuccess = null, CompleteSuccess = null, ConfirmedPresets = null };
            state = state with { Inventory = "Complete", InventorySuccess = result.PullResponse!.ServerTime,
                ServerId = result.Identity?.ServerId, AccountId = result.Identity?.AccountId, Detail = "库存已完成；BOM 预设与照片待完成。" };
            Persist(state);
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                var outbound = Presets.Snapshot();
                state = state with { Presets = "Running" }; Persist(state);
                var remote = await _apiClient.RunPresetSyncAsync(envelope.Settings, result, new BomPresetSyncRequest(envelope.Settings.DeviceId, outbound), cancellationToken);
                if (! _store.GetSyncConfiguration().MatchesConnectionDraft(envelope.Settings.ServerBaseUrl, envelope.Settings.FallbackServerBaseUrl, envelope.Settings.ApiToken, envelope.Settings.AutoSyncEnabled))
                    throw new InvalidOperationException("同步账户或连接已变化，请重试。");
                Presets.Merge(remote, outbound);
                // The server acknowledged this snapshot. Edits made during the request remain pending.
                var confirmed = SyncRunStateStore.Snapshot(outbound);
                foreach (var incoming in remote)
                    if (!confirmed.TryGetValue(incoming.Id, out var sent) || DateTimeOffset.Parse(incoming.UpdatedAt) >= DateTimeOffset.Parse(sent)) confirmed[incoming.Id] = incoming.UpdatedAt;
                state = state with { Presets = "Complete", PresetSuccess = DateTimeOffset.Now.ToString("g"), ConfirmedPresets = confirmed, Detail = "库存与 BOM 预设已完成；照片待完成。" };
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
            catch (Exception error) { state = state with { Presets = "Failed", Detail = "库存已完成；BOM 预设未完成：" + error.Message + " 本地预设已保留。" }; }
            Persist(state);
            var photos = new PhotoSyncService(_store, _apiClient);
            photos.ProgressChanged += () => ProgressChanged?.Invoke();
            var photoMessage = await photos.RunAsync(envelope.Settings, result, cancellationToken);
            var pendingPresets = SyncRunStateStore.PendingPresets(Presets.Snapshot(), state);
            var partial = state.Presets != "Complete" || pendingPresets > 0 || _store.GetPendingSyncCount() > 0 || photos.CurrentState.State != "complete";
            var finalDetail = (state.Presets == "Complete" ? "库存与 BOM 预设已完成。" : state.Detail) + " " + photoMessage;
            if (_store.GetPendingSyncCount() > 0 || pendingPresets > 0) finalDetail += " 本轮期间新增的本地修改已保留，待下一轮同步。";
            state = state with { State = partial ? "Partial" : "Complete", CompleteSuccess = partial ? state.CompleteSuccess : DateTimeOffset.Now.ToString("g"), Detail = finalDetail };
            Persist(state);
            return result.WithCompletion(StatusSummary, partial);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            Persist(state with { State = "Cancelled", Inventory = inventoryDone ? "Complete" : "Pending", Presets = state.Presets == "Complete" ? "Complete" : "Pending", Detail = inventoryDone ? "库存已完成；未完成内容保留，请重新同步。" : "本地数据和待同步内容保留，请重新同步。" });
            return inventoryDone ? result!.WithCompletion(StatusSummary, true) : SyncRunResult.Failure(StatusSummary);
        }
        catch (Exception error)
        {
            Persist(state with { State = inventoryDone ? "Partial" : "Failed", Detail = (inventoryDone ? "库存已完成；" : "") + error.Message + " 本地数据已保留。" });
            return inventoryDone ? result!.WithCompletion(StatusSummary, true) : SyncRunResult.Failure(StatusSummary);
        }
    }
}
