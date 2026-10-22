using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.ViewModels;

namespace ComponentVault.WinUI.Services;

// Owns UI sync state and scheduling. InventorySyncService owns the network exchange and account binding.
public sealed class SyncCoordinator : ObservableObject, IDisposable
{
    private static readonly TimeSpan AutoSyncDelay = TimeSpan.FromMilliseconds(750);
    private readonly InventoryStore _store;
    private readonly InventorySyncService _syncService;
    private readonly Action _refresh;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly object _autoSyncLock = new();
    private CancellationTokenSource? _scheduledSync;
    private SyncConfiguration _configuration;
    private string _statusMessage;
    private bool _isBusy;
    private bool _disposed;

    public SyncCoordinator(InventoryStore store, InventorySyncService syncService, Action refresh)
    {
        _store = store;
        _syncService = syncService;
        _refresh = refresh;
        _configuration = store.GetSyncConfiguration();
        _statusMessage = _configuration.LastSyncMessage;
    }

    public SyncConfiguration SyncConfiguration
    {
        get => _configuration;
        private set
        {
            if (SetProperty(ref _configuration, value)) NotifyConfiguration();
        }
    }

    public string StatusMessage
    {
        get => _statusMessage;
        private set => SetProperty(ref _statusMessage, value);
    }

    public bool IsBusy
    {
        get => _isBusy;
        private set
        {
            if (SetProperty(ref _isBusy, value))
            {
                OnPropertyChanged(nameof(CanRunSyncActions));
                OnPropertyChanged(nameof(SyncButtonLabel));
                OnPropertyChanged(nameof(SyncActionHint));
            }
        }
    }

    public string PhotoSyncSummary => _syncService.PhotoSummary;
    public string ClearPhotoCache() { var message = _syncService.ClearPhotoCache(); _refresh(); return message; }

    public bool CanRunSyncActions => !IsBusy;
    public string SyncButtonLabel => IsBusy ? "同步中" : "立即同步";
    public string ShellSyncSummary => string.IsNullOrWhiteSpace(SyncConfiguration.LastSyncedAt)
        ? "尚未完成同步" : $"最近同步：{SyncConfiguration.LastSyncedAt}";
    public string OverviewSyncSummary => string.IsNullOrWhiteSpace(SyncConfiguration.LastSyncMessage)
        ? $"{ShellSyncSummary} · 等待首次同步"
        : $"{ShellSyncSummary} · {SyncConfiguration.LastSyncMessage}";
    public string SyncEndpointDisplay => string.IsNullOrWhiteSpace(SyncConfiguration.ServerBaseUrl)
        ? "未配置" : SyncConfiguration.ServerBaseUrl;
    public string SyncTokenDisplay => string.IsNullOrWhiteSpace(SyncConfiguration.ApiTokenMasked)
        ? "未配置" : SyncConfiguration.ApiTokenMasked;
    public string SyncAutoSyncDisplay => SyncConfiguration.AutoSyncEnabled ? "已启用" : "未启用";
    public string SyncRiskTitle => string.IsNullOrWhiteSpace(SyncConfiguration.ServerBaseUrl)
        ? "缺少服务器地址" : string.IsNullOrWhiteSpace(SyncConfiguration.ApiToken)
            ? "缺少 API 令牌" : SyncConfiguration.ApiToken == "change-me"
                ? "请替换默认令牌" : "同步配置已就绪";
    public string SyncRiskMessage => string.IsNullOrWhiteSpace(SyncConfiguration.ServerBaseUrl)
        ? "填写服务端地址后，Windows 客户端才能执行连接测试与手动同步。"
        : string.IsNullOrWhiteSpace(SyncConfiguration.ApiToken)
            ? "当前尚未填写 API 令牌，测试连接与同步会失败。"
            : SyncConfiguration.ApiToken == "change-me"
                ? "当前仍在使用默认示例令牌，建议立即替换为真实共享令牌。"
                : "连接信息看起来完整，可以执行连接测试或立即同步。";
    public bool HasSyncRisk => string.IsNullOrWhiteSpace(SyncConfiguration.ServerBaseUrl)
        || string.IsNullOrWhiteSpace(SyncConfiguration.ApiToken)
        || SyncConfiguration.ApiToken == "change-me";
    public string SyncConfigurationSummary =>
        $"设备 {SyncConfiguration.DeviceId} · {SyncAutoSyncDisplay} · 最近结果：{SyncConfiguration.LastSyncMessage}";
    public string SyncActionHint => IsBusy
        ? "正在连接或同步库存、BOM 预设与照片，请等待当前操作完成。"
        : "所有写入仍然会先落到本地 SQLite；同步仅在你主动触发或开启自动同步后发生。";

    public void Refresh()
    {
        SyncConfiguration = _store.GetSyncConfiguration();
        StatusMessage = string.IsNullOrWhiteSpace(SyncConfiguration.LastSyncMessage)
            ? "尚未执行同步。" : SyncConfiguration.LastSyncMessage;
    }

    public OperationResult SaveConfiguration(string serverBaseUrl, string fallbackServerBaseUrl,
        string apiToken, bool autoSyncEnabled)
    {
        var result = _store.SaveSyncConfiguration(serverBaseUrl, fallbackServerBaseUrl, apiToken, autoSyncEnabled);
        _refresh();
        ScheduleAutoSync();
        return result;
    }

    public async Task<OperationResult> TestConnectionAsync(string serverBaseUrl,
        string fallbackServerBaseUrl, string apiToken, bool autoSyncEnabled)
    {
        await _gate.WaitAsync();
        try
        {
            IsBusy = true;
            var draft = SyncConfiguration.WithDraftConnection(serverBaseUrl,
                fallbackServerBaseUrl, apiToken, autoSyncEnabled);
            var result = await _syncService.TestConnectionAsync(draft);
            StatusMessage = result.Message;
            return result;
        }
        finally
        {
            IsBusy = false;
            _gate.Release();
        }
    }

    public Task<SyncRunResult> RunSyncAsync() => RunSyncAsync(CancellationToken.None);

    private async Task<SyncRunResult> RunSyncAsync(CancellationToken gateCancellationToken)
    {
        await _gate.WaitAsync(gateCancellationToken);
        try
        {
            IsBusy = true;
            var result = await _syncService.RunSyncAsync();
            _refresh();
            StatusMessage = result.IsSuccess ? SyncConfiguration.LastSyncMessage : result.Message;
            return result;
        }
        finally
        {
            IsBusy = false;
            _gate.Release();
        }
    }

    public void ScheduleAutoSync()
    {
        CancellationTokenSource? current;
        lock (_autoSyncLock)
        {
            if (_disposed) return;
            _scheduledSync?.Cancel();
            current = SyncConfiguration.AutoSyncEnabled ? new CancellationTokenSource() : null;
            _scheduledSync = current;
        }
        if (current is not null) _ = RunScheduledAutoSyncAsync(current);
    }

    private async Task RunScheduledAutoSyncAsync(CancellationTokenSource cancellation)
    {
        try
        {
            await Task.Delay(AutoSyncDelay, cancellation.Token);
            await RunSyncAsync(cancellation.Token);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (Exception exception) { StatusMessage = $"自动同步失败：{exception.Message}"; }
        finally
        {
            lock (_autoSyncLock)
            {
                if (ReferenceEquals(_scheduledSync, cancellation)) _scheduledSync = null;
            }
            cancellation.Dispose();
        }
    }

    private void NotifyConfiguration()
    {
        OnPropertyChanged(nameof(PhotoSyncSummary));
        OnPropertyChanged(nameof(ShellSyncSummary));
        OnPropertyChanged(nameof(OverviewSyncSummary));
        OnPropertyChanged(nameof(SyncEndpointDisplay));
        OnPropertyChanged(nameof(SyncTokenDisplay));
        OnPropertyChanged(nameof(SyncAutoSyncDisplay));
        OnPropertyChanged(nameof(SyncRiskTitle));
        OnPropertyChanged(nameof(SyncRiskMessage));
        OnPropertyChanged(nameof(HasSyncRisk));
        OnPropertyChanged(nameof(SyncConfigurationSummary));
    }

    public void Dispose()
    {
        lock (_autoSyncLock)
        {
            if (_disposed) return;
            _disposed = true;
            _scheduledSync?.Cancel();
            _scheduledSync = null;
        }
    }
}
