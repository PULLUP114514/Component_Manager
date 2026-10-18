using System.Globalization;
using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services.InventoryWrites;

internal sealed class InventoryComponentWriter
{
    private readonly InventoryDatabase _database;

    internal InventoryComponentWriter(InventoryDatabase database) => _database = database;

    public ComponentRecord SaveComponent(ComponentDraft draft, string? newLocationName = null)
    {
        ValidateComponentDraft(draft);

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        EnsureUniqueActiveSku(connection, draft.Sku.Trim(), draft.Id);
        if (newLocationName is not null)
        {
            var created = InventoryLocationWriter.InsertNewLocation(connection, draft.Location, newLocationName);
            if (!created.IsSuccess) throw new InvalidOperationException(created.Message);
        }

        var componentId = draft.Id ?? $"cmp-{Guid.NewGuid():N}";
        var existingComponent = draft.Id is null ? null : InventoryQueries.GetComponentById(connection, componentId);
        var locationChanged = existingComponent is not null
            && !string.Equals(existingComponent.Location, draft.Location.Trim(), StringComparison.Ordinal);
        var updatedAt = UtcNow();

        using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO components (
                id,
                sku,
                name,
                category,
                package_name,
                location,
                description,
                quantity,
                min_stock,
                updated_at,
                deleted,
                base_updated_at
            ) VALUES (
                $id,
                $sku,
                $name,
                $category,
                $package_name,
                $location,
                $description,
                $quantity,
                $min_stock,
                $updated_at,
                0,
                NULL
            )
            ON CONFLICT(id) DO UPDATE SET
                sku = excluded.sku,
                name = excluded.name,
                category = excluded.category,
                package_name = excluded.package_name,
                location = excluded.location,
                description = excluded.description,
                quantity = excluded.quantity,
                min_stock = excluded.min_stock,
                updated_at = excluded.updated_at,
                deleted = 0
            """;
        command.Parameters.AddWithValue("$id", componentId);
        command.Parameters.AddWithValue("$sku", draft.Sku.Trim());
        command.Parameters.AddWithValue("$name", draft.Name.Trim());
        command.Parameters.AddWithValue("$category", draft.Category.Trim());
        command.Parameters.AddWithValue("$package_name", draft.PackageName.Trim());
        command.Parameters.AddWithValue("$location", draft.Location.Trim());
        command.Parameters.AddWithValue("$description", draft.Description.Trim());
        command.Parameters.AddWithValue("$quantity", draft.Quantity);
        command.Parameters.AddWithValue("$min_stock", draft.MinStock);
        command.Parameters.AddWithValue("$updated_at", updatedAt);
        command.ExecuteNonQuery();

        var locationId = NormalizeLocationId(draft.Location);
        if (existingComponent is null || locationChanged)
        {
            UpsertLocalLocation(connection, locationId, locationId, updatedAt);
            SetAllocationQuantity(connection, componentId, locationId, draft.Quantity);
            using var clearOtherAllocations = connection.CreateCommand();
            clearOtherAllocations.CommandText = "UPDATE component_allocations SET quantity=0 WHERE component_id=$component_id AND location_id<>$location_id";
            clearOtherAllocations.Parameters.AddWithValue("$component_id", componentId);
            clearOtherAllocations.Parameters.AddWithValue("$location_id", locationId);
            clearOtherAllocations.ExecuteNonQuery();
        }
        else if (existingComponent.Quantity != draft.Quantity)
        {
            var adjustedQuantity = (long)GetAllocationQuantity(connection, componentId, locationId) + draft.Quantity - existingComponent.Quantity;
            if (adjustedQuantity < 0 || adjustedQuantity > int.MaxValue) throw new InvalidOperationException("The edited total would make the selected location invalid.");
            SetAllocationQuantity(connection, componentId, locationId, (int)adjustedQuantity);
        }

        EnqueueEntity(connection, "component", componentId, updatedAt);

        var savedComponent = InventoryQueries.GetComponentById(connection, componentId)
            ?? throw new InvalidOperationException("保存后未找到元器件。");
        transaction.Commit();
        return savedComponent;
    }

    public OperationResult SoftDeleteComponent(string componentId)
    {
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        if (!InventoryQueries.ComponentExists(connection, componentId))
        {
            return OperationResult.Failure("请先选择一个要删除的元器件。");
        }

        var updatedAt = UtcNow();
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            UPDATE components
            SET deleted = 1, updated_at = $updated_at
            WHERE id = $id
            """;
        command.Parameters.AddWithValue("$id", componentId);
        command.Parameters.AddWithValue("$updated_at", updatedAt);
        command.ExecuteNonQuery();

        EnqueueEntity(connection, "component", componentId, updatedAt);
        transaction.Commit();
        return OperationResult.Success("元器件已在本机软删除。");
    }

    private static void EnsureUniqueActiveSku(
        SqliteConnection connection,
        string sku,
        string? componentId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT COUNT(*)
            FROM components
            WHERE deleted = 0 AND UPPER(sku) = UPPER($sku) AND id != COALESCE($id, '')
            """;
        command.Parameters.AddWithValue("$sku", sku);
        command.Parameters.AddWithValue("$id", (object?)componentId ?? DBNull.Value);
        var duplicates = Convert.ToInt32(command.ExecuteScalar(), CultureInfo.InvariantCulture);
        if (duplicates > 0)
        {
            throw new InvalidOperationException("已存在相同 SKU 的有效元器件。");
        }
    }

    private static void ValidateComponentDraft(ComponentDraft draft)
    {
        if (string.IsNullOrWhiteSpace(draft.Sku)
            || string.IsNullOrWhiteSpace(draft.Name)
            || string.IsNullOrWhiteSpace(draft.Category)
            || string.IsNullOrWhiteSpace(draft.PackageName)
            || string.IsNullOrWhiteSpace(draft.Location))
        {
            throw new InvalidOperationException("请填写 SKU、名称、分类、封装和库位。");
        }

        if (draft.Quantity < 0 || draft.MinStock < 0)
        {
            throw new InvalidOperationException("数量和最低库存不能为负数。");
        }
    }
}
