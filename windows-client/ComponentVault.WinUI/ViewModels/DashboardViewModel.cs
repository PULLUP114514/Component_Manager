using System.Collections.ObjectModel;
using System.Linq;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;

namespace ComponentVault.WinUI.ViewModels;

public sealed class DashboardViewModel : ObservableObject
{
    private readonly InventoryStore _store;
    private DashboardSnapshot _dashboard = new()
    {
        ComponentCount = 0, TotalUnits = 0, LowStockCount = 0, MovementCount = 0,
    };

    public DashboardViewModel(InventoryStore store)
    {
        _store = store;
        LowStockComponents = [];
        OverviewLowStockComponents = [];
        OverviewRecentMovements = [];
    }

    public DashboardSnapshot Dashboard
    {
        get => _dashboard;
        private set => SetProperty(ref _dashboard, value);
    }

    public ObservableCollection<ComponentRecord> LowStockComponents { get; }
    public ObservableCollection<ComponentRecord> OverviewLowStockComponents { get; }
    public ObservableCollection<StockMovementRecord> OverviewRecentMovements { get; }

    public string OverviewLowStockSummary => OverviewLowStockComponents.Count == 0
        ? "当前没有低库存预警。"
        : $"共有 {Dashboard.LowStockCount} 项低库存，这里展示最需要关注的 {OverviewLowStockComponents.Count} 项。";

    public string OverviewRecentMovementSummary => OverviewRecentMovements.Count == 0
        ? "最近还没有新的出入库记录。"
        : $"最近 {OverviewRecentMovements.Count} 条变动可用于快速回看操作。";

    public void Refresh(IEnumerable<StockMovementRecord> movements)
    {
        Dashboard = _store.GetDashboardSnapshot();
        var locationDisplay = new StorageLocationDisplay(_store.GetStorageLocations(includeDeleted: true));
        Replace(LowStockComponents, _store.GetLowStockComponents().Select(locationDisplay.Decorate));
        Replace(OverviewLowStockComponents, LowStockComponents.Take(5));
        Replace(OverviewRecentMovements, movements.Take(5));
        OnPropertyChanged(nameof(OverviewLowStockSummary));
        OnPropertyChanged(nameof(OverviewRecentMovementSummary));
    }

    private static void Replace<T>(ObservableCollection<T> collection, IEnumerable<T> items)
    {
        collection.Clear();
        foreach (var item in items) collection.Add(item);
    }
}
