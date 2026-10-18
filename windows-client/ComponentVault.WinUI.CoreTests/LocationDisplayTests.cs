using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.ViewModels;
using Microsoft.Data.Sqlite;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class LocationDisplayTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "component-vault-location-display-" + Guid.NewGuid());

    [Fact]
    public void InventoryUsesNamesForDisplayFilterAndSearchWhileKeepingIds()
    {
        var store = CreateStore();
        Assert.True(store.CreateStorageLocation("LOC-1", "电阻抽屉").IsSuccess);
        var saved = Save(store, "R-1", "LOC-1");
        var vm = new InventoryViewModel(store);
        vm.Refresh();

        var component = Assert.Single(vm.Components);
        Assert.Equal("LOC-1", component.Location);
        Assert.Equal("电阻抽屉", component.DisplayLocation);
        Assert.Contains("电阻抽屉", vm.InventoryLocationOptions);
        vm.SelectedComponentLocationFilter = "电阻抽屉";
        vm.ComponentSearchText = "电阻抽屉";
        Assert.Equal(saved.Id, Assert.Single(vm.Components).Id);
        Assert.Equal("LOC-1", Assert.Single(vm.Components).Location);
    }

    [Fact]
    public void DuplicateNamesStaySeparateInFilter()
    {
        var store = CreateStore();
        Assert.True(store.CreateStorageLocation("LOC-1", "抽屉").IsSuccess);
        Assert.True(store.CreateStorageLocation("LOC-2", "第二抽屉").IsSuccess);
        // Existing synced data can contain repeated names even though the editor rejects new ones.
        using (var connection = new SqliteConnection($"Data Source={Path.Combine(_root, "inventory.db")}"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "UPDATE storage_locations SET name='抽屉' WHERE id='LOC-2'";
            command.ExecuteNonQuery();
        }
        var first = Save(store, "R-1", "LOC-1");
        var second = Save(store, "R-2", "LOC-2");
        var vm = new InventoryViewModel(store);
        vm.Refresh();

        Assert.Contains("抽屉 (LOC-1)", vm.InventoryLocationOptions);
        Assert.Contains("抽屉 (LOC-2)", vm.InventoryLocationOptions);
        vm.SelectedComponentLocationFilter = "抽屉 (LOC-1)";
        Assert.Equal(first.Id, Assert.Single(vm.Components).Id);
        vm.SelectedComponentLocationFilter = "抽屉 (LOC-2)";
        Assert.Equal(second.Id, Assert.Single(vm.Components).Id);
    }

    [Fact]
    public void MissingHistoricalLocationFallsBackToIdForComponentAndAllocation()
    {
        var display = new StorageLocationDisplay([]);
        var component = new ComponentRecord
        {
            Id = "C-1", Sku = "R-1", Name = "R", Category = "Resistor", PackageName = "0603",
            Location = "OLD-1", Description = "", Quantity = 2, MinStock = 1,
            UpdatedAt = "2026-01-01", Deleted = false,
            Allocations = [new("C-1", "OLD-1", 2)],
        };

        var decorated = display.Decorate(component);
        Assert.Equal("OLD-1", decorated.Location);
        Assert.Equal("OLD-1", decorated.DisplayLocation);
        Assert.Equal("OLD-1 · 2", Assert.Single(decorated.Allocations).ToString());
        Assert.Equal("OLD-1", Assert.Single(decorated.Allocations).LocationId);
    }

    private InventoryStore CreateStore()
    {
        Directory.CreateDirectory(_root);
        var store = new InventoryStore(Path.Combine(_root, "inventory.db"));
        store.Initialize();
        return store;
    }

    private static ComponentRecord Save(InventoryStore store, string sku, string location) =>
        store.SaveComponent(new ComponentDraft
        {
            Sku = sku, Name = sku, Category = "Resistor", PackageName = "0603",
            Location = location, Description = "", Quantity = 2, MinStock = 1,
        });

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
