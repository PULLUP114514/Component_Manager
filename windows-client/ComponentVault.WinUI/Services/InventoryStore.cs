using System.Globalization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.Services.Migration;
using ComponentVault.WinUI.Services.BatchInbound;
using Microsoft.Data.Sqlite;
using ComponentVault.WinUI.Services.InventoryWrites;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services;

public sealed class InventoryStore
{
    private readonly InventoryDatabase _database;
    private readonly InventoryQueries _queries;
    private readonly InventoryLocationWriter _locations;
    private readonly InventoryBatchWriter _batch;
    private readonly InventoryComponentWriter _components;
    private readonly InventoryStockWriter _stock;
    private readonly InventoryBomImportWriter _bomImport;
    internal string DatabasePath { get; }

    public InventoryStore()
        : this(
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ComponentVault",
                "component-vault.db"
            )
        )
    {
    }

    internal InventoryStore(string databasePath)
    {
        DatabasePath = databasePath;
        _database = new InventoryDatabase(databasePath, UtcNow);
        _queries = new InventoryQueries(_database.OpenConnection);
        _locations = new InventoryLocationWriter(_database);
        _batch = new InventoryBatchWriter(_database);
        _components = new InventoryComponentWriter(_database);
        _stock = new InventoryStockWriter(_database);
        _bomImport = new InventoryBomImportWriter(_database);
    }

    public void Initialize() => _database.Initialize();

    public DashboardSnapshot GetDashboardSnapshot() => _queries.GetDashboardSnapshot();
    public IReadOnlyList<ComponentRecord> GetComponents() => _queries.GetComponents();
    public IReadOnlyList<ComponentRecord> GetLowStockComponents() => _queries.GetLowStockComponents();
    public IReadOnlyList<ComponentRecord> GetActiveComponentsForSelection() => _queries.GetActiveComponentsForSelection();
    public ComponentRecord? FindActiveComponentBySku(string sku) => _queries.FindActiveComponentBySku(sku);
    public IReadOnlyList<StorageLocationRecord> GetStorageLocations(bool includeDeleted = false) => _queries.GetStorageLocations(includeDeleted);
    public IReadOnlyList<ComponentAllocationRecord> GetAllocations(string componentId) => _queries.GetAllocations(componentId);

    public OperationResult SaveStorageLocation(string id, string name) => _locations.SaveStorageLocation(id, name);
    public OperationResult CreateStorageLocation(string id, string name) => _locations.CreateStorageLocation(id, name);
    public OperationResult UpdateStorageLocation(string id, string name) => _locations.UpdateStorageLocation(id, name);
    public OperationResult DeleteStorageLocation(string id) => _locations.DeleteStorageLocation(id);

    public IReadOnlyList<StockMovementRecord> GetMovements(int limit = 200) => _queries.GetMovements(limit);

    public bool IsBatchReceiptCommitted(string receiptId) => _batch.IsBatchReceiptCommitted(receiptId);
    public static IReadOnlyList<BatchInboundSummary> SummarizeBatchInbound(IEnumerable<BatchInboundLine> lines) => InventoryBatchWriter.SummarizeBatchInbound(lines);
    public OperationResult CommitBatchInbound(BatchInboundCommitRequest request) => _batch.CommitBatchInbound(request);
    public OperationResult AppendCatalogInbound(string sku, int quantity, string locationId, string expectedUpdatedAt, string? newLocationName = null) =>
        _batch.AppendCatalogInbound(sku, quantity, locationId, expectedUpdatedAt, newLocationName);

    public IReadOnlyDictionary<string, long> GetCumulativeOutboundQuantities() => _queries.GetCumulativeOutboundQuantities();

    public SyncConfiguration GetSyncConfiguration()
    {
        using var connection = _database.OpenConnection();
        InventoryDatabase.EnsureDefaultSettings(connection);
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
                device_id,
                server_base_url,
                fallback_server_base_url,
                api_token,
                auto_sync_enabled,
                COALESCE(last_synced_at, '') AS last_synced_at,
                COALESCE(last_sync_message, '尚未同步。') AS last_sync_message
            FROM sync_settings
            WHERE id = 1
            """;

        using var reader = command.ExecuteReader();
        reader.Read();

        return new SyncConfiguration
        {
            DeviceId = reader.GetString(0),
            ServerBaseUrl = reader.GetString(1),
            FallbackServerBaseUrl = reader.GetString(2),
            ApiToken = reader.GetString(3),
            AutoSyncEnabled = reader.GetInt32(4) == 1,
            LastSyncedAt = string.IsNullOrWhiteSpace(reader.GetString(5))
                ? "从未同步"
                : reader.GetString(5),
            LastSyncMessage = reader.GetString(6),
        };
    }

    public OperationResult SaveSyncConfiguration(
        string serverBaseUrl,
        string fallbackServerBaseUrl,
        string apiToken,
        bool autoSyncEnabled
    )
    {
        using var connection = _database.OpenConnection();
        InventoryDatabase.EnsureDefaultSettings(connection);

        var normalizedUrl = NormalizeServerBaseUrl(serverBaseUrl);
        var normalizedFallbackUrl = NormalizeServerBaseUrl(fallbackServerBaseUrl);
        var normalizedToken = apiToken.Trim();

        using var command = connection.CreateCommand();
        command.CommandText =
            """
            UPDATE sync_settings
            SET
                server_base_url = $server_base_url,
                fallback_server_base_url = $fallback_server_base_url,
                api_token = $api_token,
                auto_sync_enabled = $auto_sync_enabled
            WHERE id = 1
            """;
        command.Parameters.AddWithValue("$server_base_url", normalizedUrl);
        command.Parameters.AddWithValue("$fallback_server_base_url", normalizedFallbackUrl);
        command.Parameters.AddWithValue("$api_token", normalizedToken);
        command.Parameters.AddWithValue("$auto_sync_enabled", autoSyncEnabled ? 1 : 0);
        command.ExecuteNonQuery();

        return OperationResult.Success("同步设置已保存到本机。");
    }

    public ComponentRecord SaveComponent(ComponentDraft draft, string? newLocationName = null) => _components.SaveComponent(draft, newLocationName);
    public OperationResult SoftDeleteComponent(string componentId) => _components.SoftDeleteComponent(componentId);

    public sealed record BatchTransferLine(string ComponentId, int Quantity, string ExpectedUpdatedAt);
    public sealed record BatchTransferRequest(string SourceLocationId, string DestinationLocationId, IReadOnlyList<BatchTransferLine> Lines);

    public OperationResult TransferBatch(BatchTransferRequest request) => _stock.TransferBatch(request);
    public OperationResult RecordMovement(MovementEntryDraft draft) => _stock.RecordMovement(draft);
    public BomConfirmResult ConfirmBomConsumption(BomConfirmRequest request) => _bomImport.ConfirmBomConsumption(request);
    public OperationResult ImportComponentHub(ComponentHubImportRequest request) => _bomImport.ImportComponentHub(request);

    public SyncEnvelope CreateSyncEnvelope()
    {
        using var connection = _database.OpenConnection();
        var settings = GetSyncConfiguration();
        var queuedEntities = GetQueuedEntities(connection);

        var components = new List<SyncComponentDto>();
        var stockMovements = new List<SyncStockMovementDto>();
        var storageLocations = GetAllStorageLocationDtos(connection).ToList();

        foreach (var entity in queuedEntities)
        {
            if (entity.EntityType == "component")
            {
                var component = GetComponentDtoById(connection, entity.EntityId);
                if (component is not null)
                {
                    components.Add(component);
                }
            }
            else if (entity.EntityType == "stock_movement")
            {
                var movement = GetMovementDtoById(connection, entity.EntityId);
                if (movement is not null)
                {
                    stockMovements.Add(movement);
                }
            }
            else if (entity.EntityType == "storage_location")
            {
                var location = GetStorageLocationDtoById(connection, entity.EntityId);
                if (location is not null && storageLocations.All(item => item.Id != location.Id)) storageLocations.Add(location);
            }
        }

        return new SyncEnvelope
        {
            Settings = settings,
            Cursor = GetStoredSyncCursor(connection),
            QueuedEntities = queuedEntities,
            PushRequest = new SyncPushRequest
            {
                DeviceId = settings.DeviceId,
                Components = components,
                StockMovements = stockMovements,
                StorageLocations = storageLocations,
            },
        };
    }

    public string? ValidateAndBindSyncIdentity(SyncAccountIdentity identity, SyncConfiguration settings)
    {
        if (string.IsNullOrWhiteSpace(identity.ServerId) || string.IsNullOrWhiteSpace(identity.AccountId))
            return "服务器无法确认账户身份。请先升级服务器；本地库存和同步进度已保留。";

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();
        if (!StoredSyncConfigurationMatches(connection, settings))
            return "同步期间服务器配置已变化，请重试。";

        using var read = connection.CreateCommand();
        read.CommandText = "SELECT bound_server_id, bound_account_id, last_synced_at, last_sync_cursor FROM sync_settings WHERE id = 1";
        using var reader = read.ExecuteReader();
        reader.Read();
        var boundServer = reader.GetString(0);
        var boundAccount = reader.GetString(1);
        var previouslySynced = !reader.IsDBNull(2) || !reader.IsDBNull(3);
        reader.Close();

        if (boundServer.Length != 0 || boundAccount.Length != 0)
            return boundServer == identity.ServerId && boundAccount == identity.AccountId
                ? null
                : "此本地库存已绑定其他服务器或账户。请恢复原账户密钥，或导出库存后使用新的本地工作区。";
        if (previouslySynced && identity.Role != "admin")
            return "此库存曾在记录账户身份前同步。请使用管理员密钥绑定原库存；若要切换账户，请导出数据并使用新的本地工作区。";

        using var update = connection.CreateCommand();
        update.CommandText = "UPDATE sync_settings SET bound_server_id = $server, bound_account_id = $account WHERE id = 1";
        update.Parameters.AddWithValue("$server", identity.ServerId);
        update.Parameters.AddWithValue("$account", identity.AccountId);
        update.ExecuteNonQuery();
        transaction.Commit();
        return null;
    }

    public bool ApplySyncResult(
        SyncRunResult result,
        IReadOnlyList<SyncEntityReference> pushedEntities,
        string expectedServerBaseUrl,
        SyncConfiguration? expectedSettings = null
    )
    {
        if (result.PullResponse is null)
        {
            throw new InvalidOperationException("同步成功结果必须包含拉取数据。");
        }

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        if (!StoredServerMatches(connection, expectedServerBaseUrl)
            || expectedSettings is not null && !StoredSyncConfigurationMatches(connection, expectedSettings))
        {
            transaction.Rollback();
            return false;
        }

        foreach (var location in result.PullResponse.StorageLocations)
        {
            if (HasPostSnapshotEdit(connection, pushedEntities, "storage_location", location.Id)) continue;
            UpsertRemoteStorageLocation(connection, location);
            RemoveQueuedIfSuperseded(connection, "storage_location", location.Id, location.UpdatedAt);
        }

        foreach (var component in result.PullResponse.Components)
        {
            if (HasPostSnapshotEdit(connection, pushedEntities, "component", component.Id))
            {
                var pushed = pushedEntities.FirstOrDefault(item => item.EntityType == "component" && item.EntityId == component.Id);
                if (pushed is not null && InstantsEqual(component.UpdatedAt, pushed.EntityUpdatedAt))
                    UpdateComponentBaseline(connection, component.Id, pushed.EntityUpdatedAt);
                continue;
            }
            UpsertRemoteComponent(connection, component);
            RemoveQueuedIfSuperseded(connection, "component", component.Id, component.UpdatedAt);
        }

        foreach (var movement in result.PullResponse.StockMovements)
        {
            if (HasPostSnapshotEdit(connection, pushedEntities, "stock_movement", movement.Id)) continue;
            UpsertRemoteMovement(connection, movement);
            RemoveQueuedIfSuperseded(
                connection,
                "stock_movement",
                movement.Id,
                movement.UpdatedAt
            );
        }

        foreach (var pushedEntity in pushedEntities)
        {
            if (pushedEntity.EntityType == "component"
                && !result.PullResponse.Components.Any(item => item.Id == pushedEntity.EntityId))
                UpdateComponentBaseline(connection, pushedEntity.EntityId, pushedEntity.EntityUpdatedAt);
            RemoveQueuedEntityIfSnapshotMatches(connection, pushedEntity);
        }

        UpdateStoredSyncCursor(connection, result.PullResponse.SyncCursor);

        UpdateStoredSyncStatus(
            connection,
            result.PullResponse.ServerTime,
            $"同步完成{(string.IsNullOrWhiteSpace(result.UsedServerBaseUrl) ? string.Empty : $"（{result.UsedServerBaseUrl}）")}。上传 元器件:{result.AcceptedComponents} 变动:{result.AcceptedStockMovements}；下载 元器件:{result.PullResponse.Components.Count} 变动:{result.PullResponse.StockMovements.Count}。",
            preserveTimestamp: false
        );

        transaction.Commit();
        return true;
    }

    public void UpdateSyncStatus(string message)
    {
        using var connection = _database.OpenConnection();
        UpdateStoredSyncStatus(connection, null, message, preserveTimestamp: true);
    }

    private static string NormalizeServerBaseUrl(string serverBaseUrl) =>
        serverBaseUrl.Trim().TrimEnd('/');

    private static IReadOnlyList<SyncEntityReference> GetQueuedEntities(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT entity_type, entity_id, entity_updated_at
            FROM sync_queue
            ORDER BY created_at ASC
            """;

        using var reader = command.ExecuteReader();
        var entities = new List<SyncEntityReference>();
        while (reader.Read())
        {
            entities.Add(
                new SyncEntityReference
                {
                    EntityType = reader.GetString(0),
                    EntityId = reader.GetString(1),
                    EntityUpdatedAt = reader.GetString(2),
                }
            );
        }

        return entities;
    }

    private static long? GetStoredSyncCursor(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            "SELECT last_sync_cursor FROM sync_settings WHERE id = 1";
        var value = command.ExecuteScalar();
        return value is null or DBNull ? null : Convert.ToInt64(value, CultureInfo.InvariantCulture);
    }

    private static bool StoredServerMatches(
        SqliteConnection connection,
        string expectedServerBaseUrl
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT server_base_url FROM sync_settings WHERE id = 1";
        var currentServerBaseUrl = command.ExecuteScalar() as string ?? string.Empty;
        return string.Equals(
            NormalizeServerBaseUrl(currentServerBaseUrl),
            NormalizeServerBaseUrl(expectedServerBaseUrl),
            StringComparison.Ordinal
        );
    }

    private static bool StoredSyncConfigurationMatches(
        SqliteConnection connection,
        SyncConfiguration expected
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT server_base_url, fallback_server_base_url, api_token FROM sync_settings WHERE id = 1";
        using var reader = command.ExecuteReader();
        return reader.Read()
            && string.Equals(reader.GetString(0), expected.ServerBaseUrl, StringComparison.Ordinal)
            && string.Equals(reader.GetString(1), expected.FallbackServerBaseUrl, StringComparison.Ordinal)
            && string.Equals(reader.GetString(2), expected.ApiToken, StringComparison.Ordinal);
    }

    private static SyncComponentDto? GetComponentDtoById(
        SqliteConnection connection,
        string componentId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
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
            FROM components
            WHERE id = $id
            """;
        command.Parameters.AddWithValue("$id", componentId);

        using var reader = command.ExecuteReader();
        if (!reader.Read())
        {
            return null;
        }

        var dto = new SyncComponentDto
        {
            Id = reader.GetString(0),
            Sku = reader.GetString(1),
            Name = reader.GetString(2),
            Category = reader.GetString(3),
            PackageName = reader.GetString(4),
            Location = reader.GetString(5),
            Description = reader.IsDBNull(6) ? null : reader.GetString(6),
            Quantity = reader.GetInt32(7),
            MinStock = reader.GetInt32(8),
            UpdatedAt = reader.GetString(9),
            Deleted = reader.GetInt32(10) == 1,
            BaseUpdatedAt = reader.IsDBNull(11) ? null : reader.GetString(11),
        };
        reader.Close();
        return new SyncComponentDto
        {
            Id = dto.Id, Sku = dto.Sku, Name = dto.Name, Category = dto.Category,
            PackageName = dto.PackageName, Location = dto.Location, Description = dto.Description,
            Quantity = dto.Quantity, MinStock = dto.MinStock, UpdatedAt = dto.UpdatedAt,
            Deleted = dto.Deleted, BaseUpdatedAt = dto.BaseUpdatedAt,
            Allocations = InventoryQueries.GetAllocations(connection, componentId).Select(item => new SyncAllocationDto
            {
                LocationId = item.LocationId, Quantity = item.Quantity,
            }).ToArray(),
        };
    }

    private static SyncStorageLocationDto? GetStorageLocationDtoById(SqliteConnection connection, string id)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,name,updated_at,deleted FROM storage_locations WHERE id=$id";
        command.Parameters.AddWithValue("$id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? new SyncStorageLocationDto
        {
            Id = reader.GetString(0), Name = reader.GetString(1), UpdatedAt = reader.GetString(2), Deleted = reader.GetInt32(3) != 0,
        } : null;
    }

    private static IReadOnlyList<SyncStorageLocationDto> GetAllStorageLocationDtos(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,name,updated_at,deleted FROM storage_locations ORDER BY id";
        using var reader = command.ExecuteReader();
        var result = new List<SyncStorageLocationDto>();
        while (reader.Read()) result.Add(new SyncStorageLocationDto
        {
            Id = reader.GetString(0), Name = reader.GetString(1), UpdatedAt = reader.GetString(2), Deleted = reader.GetInt32(3) != 0,
        });
        return result;
    }

    private static SyncStockMovementDto? GetMovementDtoById(
        SqliteConnection connection,
        string movementId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
                id,
                component_id,
                movement_type,
                quantity,
                reason,
                note,
                happened_at,
                updated_at,
                deleted,
                location_id,
                destination_location_id
            FROM stock_movements
            WHERE id = $id
            """;
        command.Parameters.AddWithValue("$id", movementId);

        using var reader = command.ExecuteReader();
        if (!reader.Read())
        {
            return null;
        }

        return new SyncStockMovementDto
        {
            Id = reader.GetString(0),
            ComponentId = reader.GetString(1),
            MovementType = reader.GetString(2),
            Quantity = reader.GetInt32(3),
            Reason = reader.GetString(4),
            Note = reader.IsDBNull(5) ? null : reader.GetString(5),
            HappenedAt = reader.GetString(6),
            UpdatedAt = reader.GetString(7),
            Deleted = reader.GetInt32(8) == 1,
            LocationId = reader.IsDBNull(9) ? null : reader.GetString(9),
            DestinationLocationId = reader.IsDBNull(10) ? null : reader.GetString(10),
        };
    }

    private static void RemoveQueuedIfSuperseded(
        SqliteConnection connection,
        string entityType,
        string entityId,
        string remoteUpdatedAt
    )
    {
        var queuedUpdatedAt = GetQueuedEntityUpdatedAt(connection, entityType, entityId);
        if (
            queuedUpdatedAt is not null
            && TryCompareInstants(queuedUpdatedAt, remoteUpdatedAt, out var comparison)
            && comparison <= 0
        )
        {
            RemoveQueuedEntity(connection, entityType, entityId, queuedUpdatedAt);
        }
    }

    private static void RemoveQueuedEntityIfSnapshotMatches(
        SqliteConnection connection,
        SyncEntityReference snapshot
    )
    {
        var queuedUpdatedAt = GetQueuedEntityUpdatedAt(
            connection,
            snapshot.EntityType,
            snapshot.EntityId
        );
        if (
            queuedUpdatedAt is not null
            && TryCompareInstants(queuedUpdatedAt, snapshot.EntityUpdatedAt, out var comparison)
            && comparison == 0
        )
        {
            RemoveQueuedEntity(
                connection,
                snapshot.EntityType,
                snapshot.EntityId,
                queuedUpdatedAt
            );
        }
    }

    private static string? GetQueuedEntityUpdatedAt(
        SqliteConnection connection,
        string entityType,
        string entityId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            "SELECT entity_updated_at FROM sync_queue WHERE entity_type = $entity_type AND entity_id = $entity_id";
        command.Parameters.AddWithValue("$entity_type", entityType);
        command.Parameters.AddWithValue("$entity_id", entityId);
        return command.ExecuteScalar() as string;
    }

    private static bool HasPostSnapshotEdit(SqliteConnection connection, IReadOnlyList<SyncEntityReference> pushed, string type, string id)
    {
        var current = GetQueuedEntityUpdatedAt(connection, type, id);
        if (current is null) return false;
        var snapshot = pushed.FirstOrDefault(item => item.EntityType == type && item.EntityId == id);
        return snapshot is null || !string.Equals(current, snapshot.EntityUpdatedAt, StringComparison.Ordinal);
    }

    private static void UpdateComponentBaseline(SqliteConnection connection, string id, string serverUpdatedAt)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE components SET base_updated_at=$base WHERE id=$id";
        command.Parameters.AddWithValue("$base", serverUpdatedAt);
        command.Parameters.AddWithValue("$id", id);
        command.ExecuteNonQuery();
    }

    private static bool InstantsEqual(string left, string right) => TryCompareInstants(left, right, out var comparison) && comparison == 0;

    private static void RemoveQueuedEntity(
        SqliteConnection connection,
        string entityType,
        string entityId,
        string expectedUpdatedAt
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            DELETE FROM sync_queue
            WHERE entity_type = $entity_type
              AND entity_id = $entity_id
              AND entity_updated_at = $entity_updated_at
            """;
        command.Parameters.AddWithValue("$entity_type", entityType);
        command.Parameters.AddWithValue("$entity_id", entityId);
        command.Parameters.AddWithValue("$entity_updated_at", expectedUpdatedAt);
        command.ExecuteNonQuery();
    }

    internal static bool TryCompareInstants(string left, string right, out int comparison)
    {
        if (
            DateTimeOffset.TryParse(
                left,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
                out var leftInstant
            )
            && DateTimeOffset.TryParse(
                right,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
                out var rightInstant
            )
        )
        {
            comparison = leftInstant.UtcTicks.CompareTo(rightInstant.UtcTicks);
            return true;
        }

        comparison = 0;
        return false;
    }

    private static void UpdateStoredSyncCursor(SqliteConnection connection, long? syncCursor)
    {
        if (syncCursor is < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(syncCursor));
        }

        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE sync_settings SET last_sync_cursor = $cursor WHERE id = 1";
        command.Parameters.AddWithValue("$cursor", syncCursor is null ? DBNull.Value : syncCursor.Value);
        command.ExecuteNonQuery();
    }

    private static void UpdateStoredSyncStatus(
        SqliteConnection connection,
        string? lastSyncedAt,
        string message,
        bool preserveTimestamp
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText = preserveTimestamp
            ? """
              UPDATE sync_settings
              SET last_sync_message = $message
              WHERE id = 1
              """
            : """
              UPDATE sync_settings
              SET
                  last_synced_at = $last_synced_at,
                  last_sync_message = $message
              WHERE id = 1
              """;
        command.Parameters.AddWithValue("$message", message);
        if (!preserveTimestamp)
        {
            command.Parameters.AddWithValue(
                "$last_synced_at",
                lastSyncedAt is null ? DBNull.Value : lastSyncedAt
            );
        }
        command.ExecuteNonQuery();
    }

    private static void UpsertRemoteComponent(
        SqliteConnection connection,
        SyncComponentDto component
    )
    {
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
                $deleted,
                $updated_at
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
                deleted = excluded.deleted,
                base_updated_at = excluded.updated_at
            """;
        command.Parameters.AddWithValue("$id", component.Id);
        command.Parameters.AddWithValue("$sku", component.Sku);
        command.Parameters.AddWithValue("$name", component.Name);
        command.Parameters.AddWithValue("$category", component.Category);
        command.Parameters.AddWithValue("$package_name", component.PackageName);
        command.Parameters.AddWithValue("$location", component.Location);
        command.Parameters.AddWithValue("$description", (object?)component.Description ?? DBNull.Value);
        command.Parameters.AddWithValue("$quantity", component.Quantity);
        command.Parameters.AddWithValue("$min_stock", component.MinStock);
        command.Parameters.AddWithValue("$updated_at", component.UpdatedAt);
        command.Parameters.AddWithValue("$deleted", component.Deleted ? 1 : 0);
        command.ExecuteNonQuery();
        var remoteAllocations = component.Allocations ??
            [new SyncAllocationDto { LocationId = NormalizeLocationId(component.Location), Quantity = component.Quantity }];
        if (remoteAllocations.Count == 0 || remoteAllocations.Sum(item => (long)item.Quantity) != component.Quantity)
            throw new InvalidDataException($"远端元器件 {component.Id} 的库位分配与总库存不一致。");
        using var clear = connection.CreateCommand();
        clear.CommandText = "DELETE FROM component_allocations WHERE component_id=$id";
        clear.Parameters.AddWithValue("$id", component.Id);
        clear.ExecuteNonQuery();
        foreach (var allocation in remoteAllocations)
            SetAllocationQuantity(connection, component.Id, allocation.LocationId, allocation.Quantity,
                reviveLocation: !component.Deleted);
    }

    private static void UpsertRemoteStorageLocation(SqliteConnection connection, SyncStorageLocationDto location)
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            "INSERT INTO storage_locations(id,name,updated_at,deleted) VALUES($id,$name,$updated_at,$deleted) " +
            "ON CONFLICT(id) DO UPDATE SET name=excluded.name,updated_at=excluded.updated_at,deleted=excluded.deleted " +
            "WHERE excluded.updated_at>=storage_locations.updated_at";
        command.Parameters.AddWithValue("$id", location.Id);
        command.Parameters.AddWithValue("$name", location.Name);
        command.Parameters.AddWithValue("$updated_at", location.UpdatedAt);
        command.Parameters.AddWithValue("$deleted", location.Deleted ? 1 : 0);
        command.ExecuteNonQuery();
    }

    private static void UpsertRemoteMovement(
        SqliteConnection connection,
        SyncStockMovementDto movement
    )
    {
        using var check = connection.CreateCommand();
        check.CommandText =
            "SELECT updated_at FROM stock_movements WHERE id = $id";
        check.Parameters.AddWithValue("$id", movement.Id);
        var existingUpdatedAt = check.ExecuteScalar() as string;
        if (
            existingUpdatedAt is not null
            && string.CompareOrdinal(existingUpdatedAt, movement.UpdatedAt) > 0
        )
        {
            return;
        }

        using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO stock_movements (
                id,
                component_id,
                movement_type,
                quantity,
                reason,
                note,
                happened_at,
                updated_at,
                deleted,
                location_id,
                destination_location_id
            ) VALUES (
                $id,
                $component_id,
                $movement_type,
                $quantity,
                $reason,
                $note,
                $happened_at,
                $updated_at,
                $deleted,
                $location_id,
                $destination_location_id
            )
            ON CONFLICT(id) DO UPDATE SET
                component_id = excluded.component_id,
                movement_type = excluded.movement_type,
                quantity = excluded.quantity,
                reason = excluded.reason,
                note = excluded.note,
                happened_at = excluded.happened_at,
                updated_at = excluded.updated_at,
                deleted = excluded.deleted,
                location_id = excluded.location_id,
                destination_location_id = excluded.destination_location_id
            """;
        command.Parameters.AddWithValue("$id", movement.Id);
        command.Parameters.AddWithValue("$component_id", movement.ComponentId);
        command.Parameters.AddWithValue("$movement_type", movement.MovementType);
        command.Parameters.AddWithValue("$quantity", movement.Quantity);
        command.Parameters.AddWithValue("$reason", movement.Reason);
        command.Parameters.AddWithValue(
            "$note",
            movement.Note is null ? DBNull.Value : movement.Note
        );
        command.Parameters.AddWithValue("$happened_at", movement.HappenedAt);
        command.Parameters.AddWithValue("$updated_at", movement.UpdatedAt);
        command.Parameters.AddWithValue("$deleted", movement.Deleted ? 1 : 0);
        command.Parameters.AddWithValue("$location_id", (object?)movement.LocationId ?? DBNull.Value);
        command.Parameters.AddWithValue("$destination_location_id", (object?)movement.DestinationLocationId ?? DBNull.Value);
        command.ExecuteNonQuery();
    }
}

public sealed class SyncEnvelope
{
    public required SyncConfiguration Settings { get; init; }
    public required SyncPushRequest PushRequest { get; init; }
    public long? Cursor { get; init; }
    public required IReadOnlyList<SyncEntityReference> QueuedEntities { get; init; }
}

public sealed class SyncEntityReference
{
    public required string EntityType { get; init; }
    public required string EntityId { get; init; }
    public required string EntityUpdatedAt { get; init; }
}
