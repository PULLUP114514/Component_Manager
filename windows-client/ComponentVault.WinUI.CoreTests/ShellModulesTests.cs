using System.Collections.Specialized;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.ViewModels;
using Microsoft.Data.Sqlite;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class ShellModulesTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "component-vault-shell-" + Guid.NewGuid());

    [Fact]
    public void OverviewRefreshTracksLiveTotalsAndRecentMovements()
    {
        var store = CreateStore();
        var component = Save(store, "R-1", 1, 2);
        var overview = new DashboardViewModel(store);
        var movements = new MovementsViewModel(store);

        movements.Refresh(component.Id);
        overview.Refresh(movements.Movements);
        Assert.Equal(1, overview.Dashboard.LowStockCount);
        Assert.Equal(component.Id, Assert.Single(overview.OverviewLowStockComponents).Id);
        Assert.Empty(overview.OverviewRecentMovements);

        Assert.True(movements.Record(new MovementEntryDraft
        {
            ComponentId = component.Id, MovementType = "inbound", Quantity = 3, Reason = "restock",
        }).IsSuccess);
        movements.Refresh(component.Id);
        overview.Refresh(movements.Movements);

        Assert.Equal(0, overview.Dashboard.LowStockCount);
        Assert.Empty(overview.OverviewLowStockComponents);
        Assert.Single(overview.OverviewRecentMovements);
        Assert.Single(movements.SelectedComponentRecentMovements);
    }

    [Fact]
    public void MovementRefreshKeepsSelectionWhenViewClearsItDuringCollectionReset()
    {
        var store = CreateStore();
        var component = Save(store, "R-2", 10, 1);
        var movements = new MovementsViewModel(store);
        Assert.True(movements.Record(new MovementEntryDraft
        {
            ComponentId = component.Id, MovementType = "inbound", Quantity = 2, Reason = "first",
        }).IsSuccess);
        Assert.True(movements.Record(new MovementEntryDraft
        {
            ComponentId = component.Id, MovementType = "outbound", Quantity = 1, Reason = "second",
        }).IsSuccess);
        movements.Refresh(component.Id);
        var selected = movements.Movements.Last();
        movements.SelectedMovement = selected;
        movements.Movements.CollectionChanged += (_, args) =>
        {
            if (args.Action == NotifyCollectionChangedAction.Reset)
                movements.SelectedMovement = null;
        };

        movements.Refresh(component.Id);

        Assert.Equal(selected.Id, movements.SelectedMovement?.Id);
        Assert.Equal(2, movements.SelectedComponentRecentMovements.Count);
        Assert.Equal("1", movements.InboundMovementCount);
        Assert.Equal("1", movements.OutboundMovementCount);
    }

    private InventoryStore CreateStore()
    {
        Directory.CreateDirectory(_root);
        var store = new InventoryStore(Path.Combine(_root, "inventory.db"));
        store.Initialize();
        return store;
    }

    private static ComponentRecord Save(InventoryStore store, string sku, int quantity, int minStock) =>
        store.SaveComponent(new ComponentDraft
        {
            Sku = sku, Name = sku, Category = "Resistor", PackageName = "0603",
            Location = "A", Description = "", Quantity = quantity, MinStock = minStock,
        });

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
