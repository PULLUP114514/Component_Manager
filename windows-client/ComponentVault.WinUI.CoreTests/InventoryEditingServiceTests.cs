using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using Microsoft.Data.Sqlite;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class InventoryEditingServiceTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "cv-edit-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void CommittedWrite_RemainsSuccessfulWhenRefreshFails_AndStillSchedulesSync()
    {
        var scheduled = false;
        var result = InventoryEditWriteCoordinator.AfterCommit(
            OperationResult.Success("已追加库存。"),
            () => throw new InvalidOperationException("database temporarily unavailable"),
            () => scheduled = true);

        Assert.True(result.IsSuccess);
        Assert.True(scheduled);
        Assert.Contains("数据已保存", result.Message);
        Assert.Contains("database temporarily unavailable", result.Message);
    }

    [Fact]
    public void FailedWrite_DoesNotRefreshOrScheduleSync()
    {
        var touched = false;
        var result = InventoryEditWriteCoordinator.AfterCommit(
            OperationResult.Failure("invalid SKU"),
            () => touched = true,
            () => touched = true);

        Assert.False(result.IsSuccess);
        Assert.False(touched);
    }

    [Fact]
    public void CatalogTarget_UsesStoreEvenWhenSkuIsNotInFilteredView()
    {
        var store = Store();
        var existing = store.SaveComponent(Draft("C101", "A", 4));
        var editing = new InventoryEditingService(store);
        var request = new ComponentEditRequest(Draft("c101", "A", 2), ComponentEditSource.Catalog);

        Assert.Equal(existing.Id, editing.GetCatalogAppendTarget(request)?.Id);
        var plan = editing.Prepare(request);
        Assert.Equal(existing.Id, plan.AppendTarget?.Id);
        Assert.True(editing.Commit(plan).IsSuccess);
        Assert.Equal(6, Assert.Single(store.GetComponents()).Quantity);
        Assert.Null(editing.GetCatalogAppendTarget(request with { Source = ComponentEditSource.Manual }));
        Assert.Null(editing.GetCatalogAppendTarget(request with { Draft = Draft("c101", "A", 0) }));
    }

    [Fact]
    public void DescriptionDoesNotDecideSource_AndFailedSaveLeavesNoLocationOrQueue()
    {
        var store = Store();
        store.SaveComponent(Draft("C102", "A", 1));
        var before = store.CreateSyncEnvelope().QueuedEntities.Count;
        var editing = new InventoryEditingService(store);
        var request = new ComponentEditRequest(
            Draft("C102", "NEW", 3, "官方商品页：https://example.invalid"),
            ComponentEditSource.Manual, "New bin");

        Assert.Null(editing.GetCatalogAppendTarget(request));
        Assert.Throws<InvalidOperationException>(() => editing.Save(request));
        Assert.DoesNotContain(store.GetStorageLocations(includeDeleted: true), x => x.Id == "NEW");
        Assert.Equal(before, store.CreateSyncEnvelope().QueuedEntities.Count);
        Assert.Equal(1, Assert.Single(store.GetComponents()).Quantity);
    }

    [Fact]
    public void FailureAfterLocationInsert_RollsBackLocationAndSyncQueue()
    {
        var store = Store();
        using (var connection = new SqliteConnection($"Data Source={Path.Combine(_root, "inventory.db")}"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = """
                CREATE TRIGGER reject_component BEFORE INSERT ON components
                WHEN NEW.sku = 'C-BLOCK'
                BEGIN SELECT RAISE(ABORT, 'blocked component'); END
                """;
            command.ExecuteNonQuery();
        }
        var before = store.CreateSyncEnvelope().QueuedEntities.Count;
        var editing = new InventoryEditingService(store);
        Assert.Throws<SqliteException>(() => editing.Save(new(
            Draft("C-BLOCK", "NEW", 1), ComponentEditSource.Manual, "New bin")));

        Assert.Empty(store.GetComponents());
        Assert.DoesNotContain(store.GetStorageLocations(includeDeleted: true), x => x.Id == "NEW");
        Assert.Equal(before, store.CreateSyncEnvelope().QueuedEntities.Count);
    }

    [Fact]
    public void CatalogAppend_NewLocationCommitsWithMovement_AndStaleRequestRollsBack()
    {
        var store = Store();
        var original = store.SaveComponent(Draft("C103", "A", 3));
        var editing = new InventoryEditingService(store);
        var request = new ComponentEditRequest(Draft("C103", "B", 2), ComponentEditSource.Catalog, "Bin B");

        Assert.True(editing.Append(request, original.UpdatedAt).IsSuccess);
        var current = Assert.Single(store.GetComponents());
        Assert.Equal(5, current.Quantity);
        Assert.Equal(3, current.Allocations.Single(x => x.LocationId == "A").Quantity);
        Assert.Equal(2, current.Allocations.Single(x => x.LocationId == "B").Quantity);
        Assert.Single(store.GetMovements());
        Assert.Contains(store.GetStorageLocations(), x => x.Id == "B" && x.Name == "Bin B");
        var before = store.CreateSyncEnvelope().QueuedEntities.Count;

        var stale = editing.Append(request with { Draft = Draft("C103", "C", 2), NewLocationName = "Bin C" }, original.UpdatedAt);
        Assert.False(stale.IsSuccess);
        Assert.DoesNotContain(store.GetStorageLocations(includeDeleted: true), x => x.Id == "C");
        Assert.Equal(before, store.CreateSyncEnvelope().QueuedEntities.Count);
    }

    [Fact]
    public void NewLocationNameCollision_IsRejectedInsideTransaction()
    {
        var store = Store();
        Assert.True(store.CreateStorageLocation("A", "Bin A").IsSuccess);
        var before = store.CreateSyncEnvelope().QueuedEntities.Count;
        var editing = new InventoryEditingService(store);
        var result = editing.Commit(editing.Prepare(new(
            Draft("C104", "B", 1), ComponentEditSource.Manual, " bin a ")));

        Assert.False(result.IsSuccess);
        Assert.DoesNotContain(store.GetStorageLocations(includeDeleted: true), x => x.Id == "B");
        Assert.Empty(store.GetComponents());
        Assert.Equal(before, store.CreateSyncEnvelope().QueuedEntities.Count);
    }

    private InventoryStore Store()
    {
        Directory.CreateDirectory(_root);
        var store = new InventoryStore(Path.Combine(_root, "inventory.db"));
        store.Initialize();
        return store;
    }

    private static ComponentDraft Draft(string sku, string location, int quantity, string description = "") => new()
    {
        Sku = sku, Name = "Part", Category = "test", PackageName = "0603",
        Location = location, Quantity = quantity, MinStock = 0, Description = description,
    };

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, true);
    }
}
