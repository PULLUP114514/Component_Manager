package com.componentvault.android.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.StringRes
import com.componentvault.android.R
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.SyncConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class InventorySyncService(
    private val appContext: Context,
    private val databaseHelper: InventoryDatabaseHelper,
    private val preferences: SharedPreferences,
    private val syncConfigurationLock: Any,
) {
    private val syncMutex = Mutex()

    internal suspend fun testConnection(settings: SyncConfiguration): OperationResult = withContext(Dispatchers.IO) {
        if (settings.serverBaseUrl.isBlank()) {
            return@withContext OperationResult(false, text(R.string.sync_enter_server_url_first))
        }
        if (settings.apiToken.isBlank()) {
            return@withContext OperationResult(false, text(R.string.sync_enter_api_token_first))
        }

        return@withContext try {
            val endpoint = resolveSyncEndpoint(settings)
            requireSyncIdentity(endpoint)
            OperationResult(
                isSuccess = true,
                message = text(R.string.sync_connection_ok, endpoint.probe.optString("server_time")) +
                    if (endpoint.usesExternalAddress) " " + text(R.string.sync_via_external) else "",
            )
        } catch (exception: Exception) {
            OperationResult(
                isSuccess = false,
                message = exception.message ?: text(R.string.sync_connection_failed),
            )
        }
    }

    internal suspend fun runSync(configuration: () -> SyncConfiguration): OperationResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = configuration()
            if (settings.serverBaseUrl.isBlank()) {
                return@withContext OperationResult(
                    false,
                    text(R.string.sync_enter_server_url_before_sync),
                )
            }
            if (settings.apiToken.isBlank()) {
                return@withContext OperationResult(
                    false,
                    text(R.string.sync_enter_api_token_before_sync),
                )
            }

            try {
                val endpoint = resolveSyncEndpoint(settings)
                val identity = requireSyncIdentity(endpoint)
                val serverId = identity.optString("server_id").trim()
                val accountId = identity.optString("account_id").trim()
                synchronized(syncConfigurationLock) {
                    checkSyncConfigurationUnchanged(settings)
                    val boundServer = preferences.getString(InventoryPreferenceKeys.BOUND_SERVER_ID, "").orEmpty()
                    val boundAccount = preferences.getString(InventoryPreferenceKeys.BOUND_ACCOUNT_ID, "").orEmpty()
                    check(boundServer.isBlank() && boundAccount.isBlank() ||
                        boundServer == serverId && boundAccount == accountId) {
                        text(R.string.sync_account_mismatch)
                    }
                    if (boundServer.isBlank() && boundAccount.isBlank()) {
                        val priorSync = readStoredSyncCursor() != null ||
                            preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, "").orEmpty()
                                .matches(Regex("^\\d{4}-\\d{2}-\\d{2}T.*")) ||
                            databaseHelper.readableDatabase.use { db ->
                                db.rawQuery(
                                    "SELECT 1 FROM components WHERE base_updated_at IS NOT NULL LIMIT 1",
                                    null,
                                ).use { it.moveToFirst() }
                            }
                        check(!priorSync || identity.optString("role") == "admin") {
                            text(R.string.sync_legacy_admin_only)
                        }
                        check(preferences.edit()
                            .putString(InventoryPreferenceKeys.BOUND_SERVER_ID, serverId)
                            .putString(InventoryPreferenceKeys.BOUND_ACCOUNT_ID, accountId)
                            .commit()) { text(R.string.sync_identity_save_failed) }
                    }
                }
                val pushPayload = buildPushPayload(settings.deviceId)
                val pushResponse = callJson(
                    settings = endpoint.configuration,
                    method = "POST",
                    path = "/sync/push",
                    body = pushPayload.payload,
                    accountId = accountId,
                )
                val cursor = readStoredSyncCursor()
                val pullResponse = callJson(
                    settings = endpoint.configuration,
                    method = "GET",
                    path = SyncProtocol.pullPath(cursor),
                    body = null,
                    accountId = accountId,
                )
                val rawCursor = pullResponse.opt("sync_cursor")
                val cursorDecision = SyncProtocol.cursorFromResponse(
                    hasCursor = pullResponse.has("sync_cursor"),
                    cursor = when (rawCursor) {
                        is Int -> rawCursor.toLong()
                        is Long -> rawCursor
                        else -> null
                    },
                )

                synchronized(syncConfigurationLock) {
                    checkSyncConfigurationUnchanged(settings)
                    databaseHelper.writableDatabase.use { db ->
                        db.beginTransaction()
                        try {
                            applyPullResponse(
                                db = db,
                                pullResponse = pullResponse,
                                pushedEntities = pushPayload.queuedEntities,
                            )
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                    saveSyncCursor(cursorDecision)
                }

                val acceptedComponents = pushResponse.optInt("accepted_components")
                val acceptedMovements = pushResponse.optInt("accepted_stock_movements")
                val pulledComponents = pullResponse.optJSONArray("components")?.length() ?: 0
                val pulledMovements = pullResponse.optJSONArray("stock_movements")?.length() ?: 0
                val serverTime = pullResponse.optString("server_time")
                val successMessage = text(
                    R.string.sync_complete_summary,
                    acceptedComponents,
                    acceptedMovements,
                    pulledComponents,
                    pulledMovements,
                ) + if (endpoint.usesExternalAddress) " " + text(R.string.sync_via_external) else ""

                synchronized(syncConfigurationLock) {
                    checkSyncConfigurationUnchanged(settings)
                    updateSyncStatus(
                        lastSyncedAt = serverTime,
                        message = successMessage,
                        preserveTimestamp = false,
                    )
                }

                OperationResult(
                    isSuccess = true,
                    message = successMessage,
                )
            } catch (exception: Exception) {
                updateSyncStatus(
                    lastSyncedAt = null,
                    message = exception.message ?: text(R.string.sync_failed),
                    preserveTimestamp = true,
                )
                OperationResult(
                    isSuccess = false,
                    message = exception.message ?: text(R.string.sync_failed),
                )
            }
        }
    }

    private fun buildPushPayload(deviceId: String): SyncPayload {
        databaseHelper.readableDatabase.use { db ->
            val queuedEntities = mutableListOf<QueuedEntity>()
            db.rawQuery(
                """
                SELECT entity_type, entity_id, entity_updated_at
                FROM sync_queue
                ORDER BY created_at ASC
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    queuedEntities += QueuedEntity(
                        entityType = cursor.getString(cursor.getColumnIndexOrThrow("entity_type")),
                        entityId = cursor.getString(cursor.getColumnIndexOrThrow("entity_id")),
                        entityUpdatedAt = cursor.getString(cursor.getColumnIndexOrThrow("entity_updated_at")),
                    )
                }
            }

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("inventory_protocol", 1)
                put("components", JSONArray())
                put("stock_movements", JSONArray())
                put("storage_locations", getStorageLocationsJson(db))
            }

            queuedEntities.forEach { entity ->
                when (entity.entityType) {
                    "component" -> {
                        getComponentJson(db, entity.entityId)?.let {
                            payload.getJSONArray("components").put(it)
                        }
                    }
                    "stock_movement" -> {
                        getMovementJson(db, entity.entityId)?.let {
                            payload.getJSONArray("stock_movements").put(it)
                        }
                    }
                }
            }

            return SyncPayload(
                payload = payload,
                queuedEntities = queuedEntities,
            )
        }
    }

    private fun getComponentJson(
        db: SQLiteDatabase,
        componentId: String,
    ): JSONObject? {
        db.rawQuery(
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
            WHERE id = ?
            """.trimIndent(),
            arrayOf(componentId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return JSONObject().apply {
                put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                put("sku", cursor.getString(cursor.getColumnIndexOrThrow("sku")))
                put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                put("category", cursor.getString(cursor.getColumnIndexOrThrow("category")))
                put("package_name", cursor.getString(cursor.getColumnIndexOrThrow("package_name")))
                put("location", cursor.getString(cursor.getColumnIndexOrThrow("location")))
                put("description", cursor.getString(cursor.getColumnIndexOrThrow("description")))
                put("quantity", cursor.getInt(cursor.getColumnIndexOrThrow("quantity")))
                put("min_stock", cursor.getInt(cursor.getColumnIndexOrThrow("min_stock")))
                put("updated_at", cursor.getString(cursor.getColumnIndexOrThrow("updated_at")))
                put("deleted", cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1)
                put("base_updated_at", cursor.getString(cursor.getColumnIndexOrThrow("base_updated_at")))
                put("allocations", JSONArray().apply {
                    db.rawQuery(
                        "SELECT location_id, quantity FROM component_allocations WHERE component_id = ? ORDER BY location_id",
                        arrayOf(componentId),
                    ).use { allocationCursor ->
                        while (allocationCursor.moveToNext()) put(JSONObject().apply {
                            put("location_id", allocationCursor.getString(0))
                            put("quantity", allocationCursor.getInt(1))
                        })
                    }
                })
            }
        }
    }

    private fun getMovementJson(
        db: SQLiteDatabase,
        movementId: String,
    ): JSONObject? {
        db.rawQuery(
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
            WHERE id = ?
            """.trimIndent(),
            arrayOf(movementId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return JSONObject().apply {
                put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                put("component_id", cursor.getString(cursor.getColumnIndexOrThrow("component_id")))
                put("movement_type", cursor.getString(cursor.getColumnIndexOrThrow("movement_type")))
                put("quantity", cursor.getInt(cursor.getColumnIndexOrThrow("quantity")))
                put("reason", cursor.getString(cursor.getColumnIndexOrThrow("reason")))
                put("note", cursor.getString(cursor.getColumnIndexOrThrow("note")))
                put("happened_at", cursor.getString(cursor.getColumnIndexOrThrow("happened_at")))
                put("updated_at", cursor.getString(cursor.getColumnIndexOrThrow("updated_at")))
                put("deleted", cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1)
                put("location_id", cursor.getString(cursor.getColumnIndexOrThrow("location_id")))
                put("destination_location_id", cursor.getString(cursor.getColumnIndexOrThrow("destination_location_id")))
            }
        }
    }

    private fun resolveSyncEndpoint(settings: SyncConfiguration): ResolvedSyncEndpoint<JSONObject> =
        SyncEndpointResolver.resolve(settings) { configuration, timeout ->
            callJson(configuration, "POST", "/auth/ping", null, timeout)
        }

    private fun requireSyncIdentity(endpoint: ResolvedSyncEndpoint<JSONObject>): JSONObject {
        check(endpoint.probe.optInt("inventory_protocol", 0) == 1) {
            text(R.string.sync_inventory_protocol_unavailable)
        }
        val identity = callJson(endpoint.configuration, "GET", "/auth/me", null)
        check(identity.optString("server_id").isNotBlank() && identity.optString("account_id").isNotBlank()) {
            text(R.string.sync_identity_missing_fields)
        }
        return identity
    }

    internal fun callJson(
        settings: SyncConfiguration,
        method: String,
        path: String,
        body: JSONObject?,
        timeoutMillis: Int = 60_000,
        accountId: String? = null,
    ): JSONObject {
        val connection = URL("${settings.serverBaseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer ${settings.apiToken}")
        if (accountId != null) connection.setRequestProperty("X-Component-Vault-Account-Id", accountId)
        if (method == "GET" && path.startsWith("/sync/pull")) connection.setRequestProperty("X-Component-Vault-Device-Id", settings.deviceId)
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(body.toString())
                }
            }
            val responseCode = connection.responseCode
            val responseText = readResponseText(connection, responseCode)
            if (responseCode !in 200..299) {
                if (path == "/auth/me" && responseCode == 404) {
                    throw IllegalStateException(text(R.string.sync_identity_route_unavailable))
                }
                throw IllegalStateException(buildErrorMessage(responseCode, responseText))
            }
            if (responseText.isBlank()) {
                JSONObject()
            } else {
                try {
                    JSONObject(responseText)
                } catch (exception: org.json.JSONException) {
                    if (path == "/auth/me") {
                        throw IllegalStateException(text(R.string.sync_identity_invalid_response), exception)
                    }
                    throw exception
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponseText(
        connection: HttpURLConnection,
        responseCode: Int,
    ): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader().use { it.readText() }
    }

    private fun buildErrorMessage(
        statusCode: Int,
        body: String,
    ): String {
        if (body.isBlank()) {
            return text(R.string.sync_server_error, statusCode)
        }

        return try {
            val parsedBody = JSONObject(body)
            val validationErrors = parsedBody.optJSONArray("detail")
            if (statusCode == 422 && validationErrors != null && validationErrors.length() > 0) {
                val first = validationErrors.optJSONObject(0)
                val location = first?.optJSONArray("loc")
                val path = location?.let { fields ->
                    (0 until fields.length()).joinToString(".") { fields.optString(it) }
                }.orEmpty()
                return text(
                    R.string.sync_validation_failed,
                    path,
                    first?.optString("msg").orEmpty(),
                    validationErrors.length(),
                )
            }
            val detail = parsedBody.optString("detail")
            if (detail.isBlank()) {
                text(R.string.sync_server_error_detail, statusCode, body)
            } else {
                text(R.string.sync_server_error_detail, statusCode, detail)
            }
        } catch (_: Exception) {
            text(R.string.sync_server_error_detail, statusCode, body)
        }
    }

    private fun applyPullResponse(
        db: SQLiteDatabase,
        pullResponse: JSONObject,
        pushedEntities: List<QueuedEntity>,
    ) {
        check(pullResponse.optInt("inventory_protocol", 0) == 1) {
            "The sync response is missing inventory_protocol=1. Local changes were kept."
        }
        val locations = pullResponse.optJSONArray("storage_locations") ?: JSONArray()
        for (index in 0 until locations.length()) {
            upsertRemoteStorageLocation(db, locations.getJSONObject(index))
        }
        val components = pullResponse.optJSONArray("components") ?: JSONArray()
        for (index in 0 until components.length()) {
            val component = components.getJSONObject(index)
            val id = component.getString("id")
            val pushed = pushedEntities.firstOrNull { it.entityType == "component" && it.entityId == id }
            val queued = db.rawQuery(
                "SELECT entity_updated_at FROM sync_queue WHERE entity_type = 'component' AND entity_id = ?",
                arrayOf(id),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (SyncProtocol.canApplyInventorySnapshot(queued, pushed?.entityUpdatedAt)) {
                upsertRemoteComponent(db, component)
            } else if (pushed != null) {
                // A second local edit survives the pull, based on the version actually uploaded.
                db.execSQL("UPDATE components SET base_updated_at = ? WHERE id = ?", arrayOf(pushed.entityUpdatedAt, id))
            }
        }

        val movements = pullResponse.optJSONArray("stock_movements") ?: JSONArray()
        for (index in 0 until movements.length()) {
            val movement = movements.getJSONObject(index)
            upsertRemoteMovement(db, movement)
            removeQueuedIfSuperseded(
                db = db,
                entityType = "stock_movement",
                entityId = movement.getString("id"),
                updatedAt = movement.getString("updated_at"),
            )
        }

        pushedEntities.forEach { entity ->
            if (entity.entityType == "component" && (0 until components.length()).none {
                components.getJSONObject(it).getString("id") == entity.entityId
            }) {
                db.execSQL("UPDATE components SET base_updated_at = ? WHERE id = ?", arrayOf(entity.entityUpdatedAt, entity.entityId))
            }
            removeQueuedIfSuperseded(
                db = db,
                entityType = entity.entityType,
                entityId = entity.entityId,
                updatedAt = entity.entityUpdatedAt,
            )
        }
    }

    private fun upsertRemoteComponent(
        db: SQLiteDatabase,
        component: JSONObject,
    ) {
        val values = ContentValues().apply {
            put("id", component.getString("id"))
            put("sku", component.getString("sku"))
            put("name", component.getString("name"))
            put("category", component.getString("category"))
            put("package_name", component.getString("package_name"))
            put("location", component.getString("location"))
            put("description", if (component.isNull("description")) null as String? else component.optString("description"))
            put("quantity", component.getInt("quantity"))
            put("min_stock", component.getInt("min_stock"))
            put("updated_at", component.getString("updated_at"))
            put("deleted", if (component.getBoolean("deleted")) 1 else 0)
            put("base_updated_at", component.getString("updated_at"))
        }
        db.insertWithOnConflict(
            "components",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val allocations = component.optJSONArray("allocations") ?: JSONArray().put(
            JSONObject().put("location_id", component.getString("location").trim().ifBlank { "待整理" })
                .put("quantity", component.getInt("quantity")),
        )
        run {
            db.delete("component_allocations", "component_id = ?", arrayOf(component.getString("id")))
            for (index in 0 until allocations.length()) {
                val allocation = allocations.getJSONObject(index)
                if (component.optJSONArray("allocations") == null) {
                    ensureStorageLocation(db, allocation.getString("location_id"), component.getString("updated_at"))
                }
                setAllocationQuantity(
                    db,
                    component.getString("id"),
                    allocation.getString("location_id"),
                    allocation.getInt("quantity"),
                )
            }
            verifyAllocationTotal(db, component.getString("id"), component.getInt("quantity"))
        }
    }

    private fun upsertRemoteMovement(
        db: SQLiteDatabase,
        movement: JSONObject,
    ) {
        db.rawQuery(
            "SELECT updated_at FROM stock_movements WHERE id = ?",
            arrayOf(movement.getString("id")),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val existingUpdatedAt = cursor.getString(cursor.getColumnIndexOrThrow("updated_at"))
                if (
                    !SyncProtocol.isRemoteAtLeastAsNew(
                        localUpdatedAt = existingUpdatedAt,
                        remoteUpdatedAt = movement.getString("updated_at"),
                    )
                ) {
                    return
                }
            }
        }

        val values = ContentValues().apply {
            put("id", movement.getString("id"))
            put("component_id", movement.getString("component_id"))
            put("movement_type", movement.getString("movement_type"))
            put("quantity", movement.getInt("quantity"))
            put("reason", movement.getString("reason"))
            put("note", movement.optString("note"))
            put("happened_at", movement.getString("happened_at"))
            put("updated_at", movement.getString("updated_at"))
            put("deleted", if (movement.getBoolean("deleted")) 1 else 0)
            put("location_id", movement.optString("location_id").takeIf(String::isNotBlank))
            put("destination_location_id", movement.optString("destination_location_id").takeIf(String::isNotBlank))
        }
        db.insertWithOnConflict(
            "stock_movements",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun removeQueuedIfSuperseded(
        db: SQLiteDatabase,
        entityType: String,
        entityId: String,
        updatedAt: String,
    ) {
        db.rawQuery(
            """
            SELECT entity_updated_at
            FROM sync_queue
            WHERE entity_type = ? AND entity_id = ?
            """.trimIndent(),
            arrayOf(entityType, entityId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return
            }
            val queuedUpdatedAt = cursor.getString(
                cursor.getColumnIndexOrThrow("entity_updated_at"),
            )
            if (!SyncProtocol.timestampsEqual(queuedUpdatedAt, updatedAt)) {
                return
            }
            db.delete(
                "sync_queue",
                "entity_type = ? AND entity_id = ? AND entity_updated_at = ?",
                arrayOf(entityType, entityId, queuedUpdatedAt),
            )
        }
    }

    private fun updateSyncStatus(
        lastSyncedAt: String?,
        message: String,
        preserveTimestamp: Boolean,
    ) {
        val editor = preferences.edit().putString(InventoryPreferenceKeys.LAST_SYNC_MESSAGE, message)
        if (!preserveTimestamp && lastSyncedAt != null) {
            editor.putString(InventoryPreferenceKeys.LAST_SYNCED_AT, lastSyncedAt)
        }
        editor.apply()
    }

    private fun upsertRemoteStorageLocation(db: SQLiteDatabase, location: JSONObject) {
        val id = location.getString("id")
        val remoteUpdatedAt = location.getString("updated_at")
        val existingUpdatedAt = db.rawQuery(
            "SELECT updated_at FROM storage_locations WHERE id = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (existingUpdatedAt != null && !SyncProtocol.isRemoteAtLeastAsNew(existingUpdatedAt, remoteUpdatedAt)) return
        db.insertWithOnConflict(
            "storage_locations",
            null,
            ContentValues().apply {
                put("id", id)
                put("name", location.getString("name"))
                put("updated_at", remoteUpdatedAt)
                put("deleted", if (location.getBoolean("deleted")) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun getStorageLocationsJson(db: SQLiteDatabase): JSONArray = JSONArray().apply {
        db.rawQuery("SELECT id, name, updated_at, deleted FROM storage_locations ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) put(JSONObject().apply {
                put("id", cursor.getString(0))
                put("name", cursor.getString(1))
                put("updated_at", cursor.getString(2))
                put("deleted", cursor.getInt(3) == 1)
            })
        }
    }

    private fun readStoredSyncCursor(): Long? = if (preferences.contains(InventoryPreferenceKeys.SYNC_CURSOR)) {
        preferences.getLong(InventoryPreferenceKeys.SYNC_CURSOR, 0L).coerceAtLeast(0L)
    } else {
        null
    }

    private fun saveSyncCursor(decision: CursorDecision) {
        val editor = preferences.edit()
        when (decision) {
            is CursorDecision.Store -> editor.putLong(InventoryPreferenceKeys.SYNC_CURSOR, decision.value)
            CursorDecision.Clear -> editor.remove(InventoryPreferenceKeys.SYNC_CURSOR)
        }
        check(editor.commit()) { "Unable to persist sync cursor" }
    }

    private fun checkSyncConfigurationUnchanged(settings: SyncConfiguration) {
        val currentServerUrl = preferences.getString(InventoryPreferenceKeys.SERVER_BASE_URL, "").orEmpty()
        val currentApiToken = preferences.getString(InventoryPreferenceKeys.API_TOKEN, "").orEmpty()
        val currentExternalUrl = preferences.getString(InventoryPreferenceKeys.EXTERNAL_SERVER_BASE_URL, "").orEmpty()
        check(
            currentServerUrl == settings.serverBaseUrl &&
                currentExternalUrl == settings.externalServerBaseUrl &&
                currentApiToken == settings.apiToken,
        ) {
            "Sync configuration changed while synchronization was running. Please retry."
        }
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String =
        appContext.getString(resId, *args)

    private data class SyncPayload(
        val payload: JSONObject,
        val queuedEntities: List<QueuedEntity>,
    )

    private data class QueuedEntity(
        val entityType: String,
        val entityId: String,
        val entityUpdatedAt: String,
    )

}
