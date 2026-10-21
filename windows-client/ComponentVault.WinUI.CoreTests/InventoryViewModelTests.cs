using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.ViewModels;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class InventoryViewModelTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "component-vault-inventory-vm-" + Guid.NewGuid());

    [Fact]
    public void FiltersComposeAndSelectionTracksVisibleResult()
    {
        var store = CreateStore();
        var resistor = Save(store, "R-1", "10k resistor", "Resistor", "A", 1, 2);
        Save(store, "R-2", "1k resistor", "Resistor", "B", 10, 2);
        Save(store, "C-1", "10k capacitor", "Capacitor", "A", 1, 2);
        var vm = new InventoryViewModel(store);
        vm.Refresh();

        vm.ComponentSearchText = "10k";
        vm.SelectedComponentCategoryFilter = "Resistor";
        vm.SelectedComponentLocationFilter = "A";
        vm.ShowLowStockOnly = true;
        Assert.Equal([resistor.Id], vm.Components.Select(item => item.Id));
        Assert.Equal(resistor.Id, vm.SelectedComponent?.Id);

        vm.ComponentSearchText = "missing";
        Assert.Empty(vm.Components);
        Assert.Null(vm.SelectedComponent);
        vm.ComponentSearchText = "10k";
        Assert.Equal(resistor.Id, vm.SelectedComponent?.Id);
    }

    [Fact]
    public void RefreshKeepsSelectionByIdAndResetsUnavailableFilters()
    {
        var store = CreateStore();
        Save(store, "A-1", "Alpha", "Resistor", "A", 4, 1);
        var chosen = Save(store, "B-1", "Beta", "Capacitor", "B", 3, 1);
        var vm = new InventoryViewModel(store);
        vm.Refresh();
        vm.SelectedComponent = vm.Components.Single(item => item.Id == chosen.Id);
        vm.Components.CollectionChanged += (_, args) =>
        {
            if (args.Action == System.Collections.Specialized.NotifyCollectionChangedAction.Reset)
                vm.SelectedComponent = null; // ListView clears SelectedItem during ItemsSource reset.
        };
        vm.Refresh();
        Assert.Equal(chosen.Id, vm.SelectedComponent?.Id);
        Assert.Same(vm.Components.Single(item => item.Id == chosen.Id), vm.SelectedComponent);

        vm.InventoryCategoryOptions.CollectionChanged += (_, args) =>
        {
            if (args.Action == System.Collections.Specialized.NotifyCollectionChangedAction.Reset)
                vm.SelectedComponentCategoryFilter = null!;
        };
        vm.SelectedComponentCategoryFilter = "Capacitor";
        vm.SelectedComponentLocationFilter = "B";
        store.SoftDeleteComponent(chosen.Id);
        vm.Refresh();
        Assert.Equal("全部分类", vm.SelectedComponentCategoryFilter);
        Assert.Equal("全部仓位", vm.SelectedComponentLocationFilter);
        Assert.Equal("A-1", vm.SelectedComponent?.Sku);
    }

    [Fact]
    public void DetailShowsStockedLocationAfterEditAndHomeLocationWhenDepleted()
    {
        var store = CreateStore();
        var original = Save(store, "LOCATION-EDIT", "Part", "IC", "A", 10, 0);
        store.SaveComponent(new ComponentDraft
        {
            Id = original.Id, Sku = original.Sku, Name = original.Name,
            Category = original.Category, PackageName = original.PackageName,
            Location = "C", Description = original.Description, Quantity = 10, MinStock = 0,
        });
        var vm = new InventoryViewModel(store);
        vm.Refresh();

        Assert.Equal("C", Assert.Single(vm.SelectedComponent!.DisplayAllocations).LocationId);
        Assert.Contains(vm.SelectedComponent.Allocations, allocation =>
            allocation.LocationId == "A" && allocation.Quantity == 0);

        store.SaveComponent(new ComponentDraft
        {
            Id = original.Id, Sku = original.Sku, Name = original.Name,
            Category = original.Category, PackageName = original.PackageName,
            Location = "C", Description = original.Description, Quantity = 0, MinStock = 0,
        });
        vm.Refresh();
        var empty = Assert.Single(vm.SelectedComponent!.DisplayAllocations);
        Assert.Equal("C", empty.LocationId);
        Assert.Equal(0, empty.Quantity);
    }

    private InventoryStore CreateStore()
    {
        Directory.CreateDirectory(_root);
        var store = new InventoryStore(Path.Combine(_root, "inventory.db"));
        store.Initialize();
        return store;
    }

    private static ComponentRecord Save(InventoryStore store, string sku, string name,
        string category, string location, int quantity, int minStock) =>
        store.SaveComponent(new ComponentDraft
        {
            Sku = sku, Name = name, Category = category, PackageName = "0603",
            Location = location, Description = "", Quantity = quantity, MinStock = minStock,
        });

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}

