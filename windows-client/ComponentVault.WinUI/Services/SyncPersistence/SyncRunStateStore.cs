using System.Text.Json;
using ComponentVault.WinUI.Services.Bom;

namespace ComponentVault.WinUI.Services.SyncPersistence;

public sealed record SyncRunState(
    string State = "Pending", string Inventory = "Pending", string Presets = "Pending",
    string? InventorySuccess = null, string? PresetSuccess = null, string? CompleteSuccess = null,
    string Detail = "", string ServerUrl = "", string? ServerId = null, string? AccountId = null,
    Dictionary<string, string>? ConfirmedPresets = null);

// Only progress metadata lives here. Business data and its retry queue stay in their existing stores.
public sealed class SyncRunStateStore(string databasePath)
{
    private string StatePath => Path.Combine(Path.GetDirectoryName(databasePath)!, "sync-run-state.json");
    public SyncRunState Read()
    {
        try { return JsonSerializer.Deserialize<SyncRunState>(File.ReadAllText(StatePath)) ?? new(); }
        catch (Exception error) when (error is IOException or JsonException) { return new(); }
    }
    public void Save(SyncRunState state)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(StatePath)!);
        var temporary = StatePath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(state));
        File.Move(temporary, StatePath, true);
    }
    public void RecoverInterrupted()
    {
        var state = Read();
        if (state.State == "Running") Save(state with { State = "Interrupted", Detail = "上次同步已中断；本地数据与已完成结果保留，请重新同步。" });
    }
    public static Dictionary<string, string> Snapshot(IReadOnlyList<BomPresetRecord> records) =>
        records.ToDictionary(item => item.Id, item => item.UpdatedAt, StringComparer.Ordinal);
    public static int PendingPresets(IReadOnlyList<BomPresetRecord> records, SyncRunState state) =>
        records.Count(item => state.ConfirmedPresets is null || !state.ConfirmedPresets.TryGetValue(item.Id, out var timestamp) || timestamp != item.UpdatedAt);
}
