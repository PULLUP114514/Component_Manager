using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using Microsoft.Data.Sqlite;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class InventoryLocationDeletionTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "cv-location-delete-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void PositiveStockOnActiveComponent_PreventsLocationDeletion()
    {
        var store = CreateStore();
        store.SaveComponent(Draft());

        var result = store.DeleteStorageLocation("BIN-1");

        Assert.False(result.IsSuccess);
        Assert.Contains(store.GetStorageLocations(), location => location.Id == "BIN-1");
        Assert.Equal(0, Scalar("SELECT deleted FROM storage_locations WHERE id='BIN-1'"));
    }

    [Fact]
    public void DeletedComponent_AllowsLocationDeletion_WithoutRemovingAllocationHistoryOrTombstones()
    {
        var store = CreateStore();
        var component = store.SaveComponent(Draft());
        Assert.True(store.RecordMovement(new MovementEntryDraft
        {
            ComponentId = component.Id,
            MovementType = "outbound",
            Quantity = 1,
            Reason = "test",
            LocationId = "BIN-1",
        }).IsSuccess);
        Assert.True(store.SoftDeleteComponent(component.Id).IsSuccess);

        var result = store.DeleteStorageLocation("BIN-1");

        Assert.True(result.IsSuccess);
        Assert.Equal(1, Scalar("SELECT COUNT(*) FROM components WHERE deleted=1"));
        Assert.Equal(2, Scalar("SELECT quantity FROM component_allocations WHERE location_id='BIN-1'"));
        Assert.Equal(1, Scalar("SELECT COUNT(*) FROM stock_movements WHERE location_id='BIN-1'"));
        Assert.Equal(1, Scalar("SELECT deleted FROM storage_locations WHERE id='BIN-1'"));
        var queue = store.CreateSyncEnvelope().QueuedEntities;
        Assert.Contains(queue, entity => entity.EntityType == "component" && entity.EntityId == component.Id);
        Assert.Contains(queue, entity => entity.EntityType == "storage_location" && entity.EntityId == "BIN-1");
    }

    [Fact]
    public void PullingDeletedComponentAllocation_DoesNotReviveDeletedLocation()
    {
        var store = CreateStore();
        var component = store.SaveComponent(Draft());
        Assert.True(store.SoftDeleteComponent(component.Id).IsSuccess);
        Assert.True(store.DeleteStorageLocation("BIN-1").IsSuccess);
        var snapshot = store.CreateSyncEnvelope();
        var remoteComponent = Assert.Single(snapshot.PushRequest.Components);
        var remoteLocation = Assert.Single(snapshot.PushRequest.StorageLocations);

        var applied = store.ApplySyncResult(new SyncRunResult
        {
            IsSuccess = true,
            Message = "ok",
            PullResponse = new SyncPullResponse
            {
                ServerTime = remoteLocation.UpdatedAt,
                Components = [remoteComponent],
                StorageLocations = [remoteLocation],
            },
        }, [], string.Empty);

        Assert.True(applied);
        Assert.Equal(1, Scalar("SELECT deleted FROM storage_locations WHERE id='BIN-1'"));
        Assert.Equal(3, Scalar("SELECT quantity FROM component_allocations WHERE location_id='BIN-1'"));
    }

    private InventoryStore CreateStore()
    {
        Directory.CreateDirectory(_directory);
        var store = new InventoryStore(Path.Combine(_directory, "inventory.db"));
        store.Initialize();
        return store;
    }

    private static ComponentDraft Draft() => new()
    {
        Sku = "C-LOCATION", Name = "Part", Category = "test", PackageName = "0603",
        Location = "BIN-1", Quantity = 3, MinStock = 0,
    };

    private long Scalar(string sql)
    {
        using var connection = new SqliteConnection($"Data Source={Path.Combine(_directory, "inventory.db")}");
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = sql;
        return Convert.ToInt64(command.ExecuteScalar());
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_directory)) Directory.Delete(_directory, recursive: true);
    }
}
