using System.Globalization;
using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;

namespace ComponentVault.WinUI.Services.SyncPersistence;

internal sealed class SyncSettingsRepository(InventoryDatabase database)
{
    private readonly InventoryDatabase _database = database;

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

    public void UpdateSyncStatus(string message)
    {
        using var connection = _database.OpenConnection();
        UpdateStoredSyncStatus(connection, null, message, preserveTimestamp: true);
    }

    private static string NormalizeServerBaseUrl(string serverBaseUrl) =>
        serverBaseUrl.Trim().TrimEnd('/');

    internal static long? GetStoredSyncCursor(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            "SELECT last_sync_cursor FROM sync_settings WHERE id = 1";
        var value = command.ExecuteScalar();
        return value is null or DBNull ? null : Convert.ToInt64(value, CultureInfo.InvariantCulture);
    }

    internal static bool StoredServerMatches(
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

    internal static bool StoredSyncConfigurationMatches(
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

    internal static void UpdateStoredSyncCursor(SqliteConnection connection, long? syncCursor)
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

    internal static void UpdateStoredSyncStatus(
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

}
