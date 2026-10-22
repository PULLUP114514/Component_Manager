using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.Services;

namespace ComponentVault.WinUI.Models;

public sealed class SyncRunResult
{
    public required bool IsSuccess { get; init; }
    public required string Message { get; init; }
    public bool IsPartial { get; init; }
    public SyncRunResult WithCompletion(string message, bool partial) => new()
    {
        IsSuccess = IsSuccess, IsPartial = partial, Message = message, PhotoProtocol = PhotoProtocol, Identity = Identity,
        AcceptedComponents = AcceptedComponents, AcceptedStockMovements = AcceptedStockMovements, UsedServerBaseUrl = UsedServerBaseUrl,
        PullResponse = PullResponse, BomPresets = BomPresets, PresetSyncMessage = PresetSyncMessage,
    };
    public int PhotoProtocol { get; init; }
    public SyncAccountIdentity? Identity { get; init; }
    public int AcceptedComponents { get; init; }
    public int AcceptedStockMovements { get; init; }
    public string UsedServerBaseUrl { get; init; } = string.Empty;
    public SyncPullResponse? PullResponse { get; init; }
    public IReadOnlyList<BomPresetRecord>? BomPresets { get; init; }
    public string PresetSyncMessage { get; init; } = "";

    public static SyncRunResult Failure(string message) =>
        new()
        {
            IsSuccess = false,
            Message = message,
        };
}
