using System.Globalization;
using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services.InventoryWrites;

internal sealed class InventoryLocationWriter
{
    private readonly InventoryDatabase _database;

    internal InventoryLocationWriter(InventoryDatabase database) => _database = database;

    public OperationResult SaveStorageLocation(string id, string name)
    {
        return CreateStorageLocation(id, name);
    }

    public OperationResult CreateStorageLocation(string id, string name)
    {
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();
        var result = InsertNewLocation(connection, id, name);
        if (!result.IsSuccess) return result;
        transaction.Commit();
        return OperationResult.Success("库位已新建。");
    }

    internal static OperationResult InsertNewLocation(SqliteConnection connection, string id, string name)
    {
        var validation = ValidateStorageLocation(id, name);
        if (!validation.IsSuccess) return validation;
        var normalized = id.Trim();
        var normalizedName = name.Trim();
        using (var duplicateName = connection.CreateCommand())
        {
            duplicateName.CommandText =
                "SELECT EXISTS(SELECT 1 FROM storage_locations WHERE deleted=0 AND name=$name COLLATE NOCASE)";
            duplicateName.Parameters.AddWithValue("$name", normalizedName);
            if (Convert.ToInt64(duplicateName.ExecuteScalar(), CultureInfo.InvariantCulture) != 0)
                return OperationResult.Failure("库位名称已存在，请选择已有库位。");
        }
        var updatedAt = UtcNow();
        using var insert = connection.CreateCommand();
        insert.CommandText = "INSERT INTO storage_locations(id,name,updated_at,deleted) VALUES($id,$name,$updated_at,0) ON CONFLICT(id) DO NOTHING";
        insert.Parameters.AddWithValue("$id", normalized);
        insert.Parameters.AddWithValue("$name", normalizedName);
        insert.Parameters.AddWithValue("$updated_at", updatedAt);
        if (insert.ExecuteNonQuery() == 0)
            return OperationResult.Failure("库位编码已存在；原库位数据已保留。");
        EnqueueEntity(connection, "storage_location", normalized, updatedAt);
        return OperationResult.Success("库位已新建。");
    }

    public OperationResult UpdateStorageLocation(string id, string name)
    {
        var validation = ValidateStorageLocation(id, name);
        if (!validation.IsSuccess) return validation;
        var normalized = id.Trim();
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();
        var updatedAt = UtcNow();
        using var update = connection.CreateCommand();
        update.CommandText = "UPDATE storage_locations SET name=$name,updated_at=$updated_at WHERE id=$id AND deleted=0";
        update.Parameters.AddWithValue("$name", name.Trim());
        update.Parameters.AddWithValue("$updated_at", updatedAt);
        update.Parameters.AddWithValue("$id", normalized);
        if (update.ExecuteNonQuery() == 0)
            return OperationResult.Failure("未找到可编辑的有效库位。");
        EnqueueEntity(connection, "storage_location", normalized, updatedAt);
        transaction.Commit();
        return OperationResult.Success("库位已更新。");
    }

    private static OperationResult ValidateStorageLocation(string id, string name)
    {
        if (string.IsNullOrWhiteSpace(id)) return OperationResult.Failure("库位编码不能为空。");
        if (id.Trim().Length > 120 || string.IsNullOrWhiteSpace(name) || name.Trim().Length > 200)
            return OperationResult.Failure("库位编码或名称无效。");
        return OperationResult.Success(string.Empty);
    }

    public OperationResult DeleteStorageLocation(string id)
    {
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();
        using var positive = connection.CreateCommand();
        positive.CommandText = """
            SELECT COALESCE(SUM(a.quantity), 0)
            FROM component_allocations AS a
            JOIN components AS c ON c.id = a.component_id
            WHERE a.location_id = $id AND c.deleted = 0
            """;
        positive.Parameters.AddWithValue("$id", id);
        if (Convert.ToInt64(positive.ExecuteScalar(), CultureInfo.InvariantCulture) > 0)
            return OperationResult.Failure("该库位仍有正库存分配，不能删除。");
        var updatedAt = UtcNow();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE storage_locations SET deleted=1,updated_at=$at WHERE id=$id AND deleted=0";
        command.Parameters.AddWithValue("$at", updatedAt);
        command.Parameters.AddWithValue("$id", id);
        if (command.ExecuteNonQuery() == 0)
            return OperationResult.Failure("未找到可删除库位。");
        EnqueueEntity(connection, "storage_location", id, updatedAt);
        transaction.Commit();
        return OperationResult.Success("库位已软删除；历史分配和出入库记录保留。");
    }
}
