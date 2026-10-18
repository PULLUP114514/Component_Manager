using System.Collections.ObjectModel;
using System.Linq;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;

namespace ComponentVault.WinUI.ViewModels;

public sealed class MovementsViewModel : ObservableObject
{
    private readonly InventoryStore _store;
    private StockMovementRecord? _selectedMovement;
    private string? _selectedComponentId;

    public MovementsViewModel(InventoryStore store)
    {
        _store = store;
        Movements = [];
        SelectedComponentRecentMovements = [];
    }

    public ObservableCollection<StockMovementRecord> Movements { get; }
    public ObservableCollection<StockMovementRecord> SelectedComponentRecentMovements { get; }

    public StockMovementRecord? SelectedMovement
    {
        get => _selectedMovement;
        set
        {
            if (SetProperty(ref _selectedMovement, value)) NotifySelection();
        }
    }

    public string MovementFeedSummary => $"本机已记录 {Movements.Count} 条库存变动。";
    public string MovementWorkspaceSummary =>
        $"入库 {InboundMovementCount} · 出库 {OutboundMovementCount} · 调整 {AdjustmentMovementCount} · 净变化 {MovementNetQuantityText}";
    public string InboundMovementCount => Movements.Count(m => m.MovementType == "inbound").ToString();
    public string OutboundMovementCount => Movements.Count(m => m.MovementType == "outbound").ToString();
    public string AdjustmentMovementCount => Movements.Count(m => m.MovementType == "adjustment").ToString();
    public string MovementNetQuantityText => Movements.Sum(m => m.QuantityChange).ToString("+#,0;-#,0;0");

    public string SelectedMovementTitle => SelectedMovement?.ComponentName ?? "尚未选择变动记录";
    public string SelectedMovementSubtitle => SelectedMovement is null
        ? "从左侧台账中选择一条记录，以查看数量变化、原因和审计字段。"
        : $"{SelectedMovement.ComponentSku} | {SelectedMovement.MovementTypeLabel}";
    public string SelectedMovementQuantityText => SelectedMovement?.QuantityLabel ?? "0";
    public string SelectedMovementTypeText => SelectedMovement?.MovementTypeLabel ?? "等待选择";
    public string SelectedMovementReason => string.IsNullOrWhiteSpace(SelectedMovement?.Reason)
        ? "未填写原因。" : SelectedMovement.Reason;
    public string SelectedMovementNote => string.IsNullOrWhiteSpace(SelectedMovement?.Note)
        ? "未填写备注。" : SelectedMovement.Note;
    public string SelectedMovementUpdatedAt => SelectedMovement?.DisplayUpdatedAt ?? "尚未选择";
    public string SelectedMovementTimeline => SelectedMovement?.DisplayTimeline ?? "尚未选择记录";
    public string SelectedMovementStatusTitle => SelectedMovement?.MovementTypeLabel ?? "审计详情";
    public string SelectedMovementStatusMessage => SelectedMovement is null
        ? "选择一条记录后，这里会显示对库存的影响和原因摘要。"
        : SelectedMovement.MovementType switch
        {
            "inbound" => "这条记录会增加关联元器件的库存数量。",
            "outbound" => "这条记录会减少关联元器件的库存数量。",
            "adjustment" => "这条记录用于手动修正关联元器件的库存数量。",
            _ => "这条记录已经写入本地审计台账。",
        };
    public string SelectedMovementActionHint =>
        "入库和出库请使用正数；库存调整可以填写正数或负数。";

    public void Refresh(string? selectedComponentId)
    {
        var selectedId = SelectedMovement?.Id;
        Movements.Clear();
        foreach (var movement in _store.GetMovements()) Movements.Add(movement);
        SelectedMovement = Movements.FirstOrDefault(m => m.Id == selectedId)
            ?? Movements.FirstOrDefault();
        NotifyTotals();
        SetSelectedComponent(selectedComponentId);
    }

    public void SetSelectedComponent(string? componentId)
    {
        _selectedComponentId = componentId;
        SelectedComponentRecentMovements.Clear();
        if (!string.IsNullOrWhiteSpace(_selectedComponentId))
        {
            foreach (var movement in Movements.Where(m => m.ComponentId == _selectedComponentId).Take(4))
                SelectedComponentRecentMovements.Add(movement);
        }
        OnPropertyChanged(nameof(SelectedComponentRecentMovements));
    }

    public OperationResult Record(MovementEntryDraft draft)
    {
        try { return _store.RecordMovement(draft); }
        catch (Exception exception) { return OperationResult.Failure(exception.Message); }
    }

    public void SelectLatestForComponent(string componentId) =>
        SelectedMovement = Movements.FirstOrDefault(m => m.ComponentId == componentId);

    private void NotifyTotals()
    {
        OnPropertyChanged(nameof(MovementFeedSummary));
        OnPropertyChanged(nameof(MovementWorkspaceSummary));
        OnPropertyChanged(nameof(InboundMovementCount));
        OnPropertyChanged(nameof(OutboundMovementCount));
        OnPropertyChanged(nameof(AdjustmentMovementCount));
        OnPropertyChanged(nameof(MovementNetQuantityText));
    }

    private void NotifySelection()
    {
        OnPropertyChanged(nameof(SelectedMovementTitle));
        OnPropertyChanged(nameof(SelectedMovementSubtitle));
        OnPropertyChanged(nameof(SelectedMovementQuantityText));
        OnPropertyChanged(nameof(SelectedMovementTypeText));
        OnPropertyChanged(nameof(SelectedMovementReason));
        OnPropertyChanged(nameof(SelectedMovementNote));
        OnPropertyChanged(nameof(SelectedMovementUpdatedAt));
        OnPropertyChanged(nameof(SelectedMovementTimeline));
        OnPropertyChanged(nameof(SelectedMovementStatusTitle));
        OnPropertyChanged(nameof(SelectedMovementStatusMessage));
        OnPropertyChanged(nameof(SelectedMovementActionHint));
    }
}
