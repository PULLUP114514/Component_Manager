using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public sealed class InventorySyncService
{
    private readonly InventoryStore _store;
    private readonly SyncApiClient _apiClient;

    public InventorySyncService(InventoryStore store, SyncApiClient apiClient)
    {
        _store = store;
        _apiClient = apiClient;
    }

    public string PhotoSummary => new PhotoSyncService(_store, _apiClient).Summary;
    public string ClearPhotoCache() => new PhotoSyncService(_store, _apiClient).ClearCache();

    public Task<OperationResult> TestConnectionAsync(CancellationToken cancellationToken = default)
    {
        return _apiClient.TestConnectionAsync(_store.GetSyncConfiguration(), cancellationToken);
    }

    public Task<OperationResult> TestConnectionAsync(
        SyncConfiguration draft,
        CancellationToken cancellationToken = default
    )
    {
        return _apiClient.TestConnectionAsync(draft, cancellationToken);
    }

    public async Task<SyncRunResult> RunSyncAsync(CancellationToken cancellationToken = default)
    {
        var envelope = _store.CreateSyncEnvelope();
        var presets = new BomPresetStore(Path.Combine(Path.GetDirectoryName(_store.DatabasePath)!, "bom-presets.json"));
        IReadOnlyList<BomPresetRecord>? outbound = null;
        string? presetError = null;
        try { outbound = presets.Snapshot(); } catch (Exception error) { presetError = error.Message; }
        var result = await _apiClient.RunSyncAsync(
            envelope.Settings,
            envelope.PushRequest,
            envelope.Cursor,
            cancellationToken,
            identity => _store.ValidateAndBindSyncIdentity(identity, envelope.Settings),
            outbound is null ? null : new BomPresetSyncRequest(envelope.Settings.DeviceId, outbound)
        );

        if (result.IsSuccess)
        {
            if (
                !_store.ApplySyncResult(
                    result,
                    envelope.QueuedEntities,
                    envelope.Settings.ServerBaseUrl,
                    envelope.Settings
                )
            )
            {
                result = SyncRunResult.Failure(
                    "同步期间服务器配置已变化，本次旧服务器结果未应用。"
                );
                _store.UpdateSyncStatus(result.Message);
            }
        }
        else
        {
            _store.UpdateSyncStatus(result.Message);
        }

        if (result.IsSuccess)
        {
            var message = result.PresetSyncMessage;
            if (result.BomPresets is not null && outbound is not null)
            {
                try { presets.Merge(result.BomPresets, outbound); }
                catch (Exception error) { presetError = error.Message; }
            }
            if (presetError is not null) message = $"库存同步完成；BOM 预设未同步：{presetError} 本地预设已保留，可重试。";
            var photos = new PhotoSyncService(_store, _apiClient);
            try
            {
                var photoMessage = await photos.RunAsync(envelope.Settings, result, cancellationToken);
                var partial = presetError is not null || result.BomPresets is null || photos.ReadState().State != "complete";
                message = (partial ? "部分完成：库存已完成。" : "同步完成：库存已完成。") + " " + message + " " + photoMessage;
                _store.UpdateSyncStatus(message);
                result = result.WithCompletion(message, partial);
            }
            catch (OperationCanceledException)
            {
                _store.UpdateSyncStatus("部分完成：库存已完成；照片同步已取消，已完成文件保留，请重试。 " + message);
                throw;
            }
        }
        return result;
    }
}
