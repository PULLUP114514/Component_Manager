package com.componentvault.android.data

import android.app.Application
import android.content.Context
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomPreset
import com.componentvault.android.data.bom.BomPresetStore
import com.componentvault.android.data.bom.BomSheet
import com.componentvault.android.model.SyncConfiguration
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SyncRecoveryTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE)
    private val inventoryTime = "2026-10-22T02:00:00Z"

    @Test fun inventorySuccessSurvivesBomFailureAndConfigurationChangeHidesOldSuccess(): Unit = runBlocking {
        withServer(cancelBom = false) { service, settings, _, _ ->
            val result = service.runSync { settings }
            assertFalse(result.isSuccess)
            assertEquals("Partial", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, ""))
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
            assertEquals("", preferences.getString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, ""))
            val repository = InventoryRepository(context)
            assertEquals(inventoryTime, repository.loadSyncConfiguration().lastSyncedAt)
            repository.saveSyncConfiguration("http://different-server", "different-key", false)
            val changed = repository.loadSyncConfiguration()
            assertEquals("", changed.syncState)
            assertTrue(changed.lastSyncedAt != inventoryTime)
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
        }
    }

    @Test fun cancelDuringBomKeepsInventorySuccessAndRecordsCancelledPhase(): Unit = runBlocking {
        withServer(cancelBom = true) { service, settings, bomStarted, releaseBom ->
            val run = async(Dispatchers.IO) { service.runSync { settings } }
            bomStarted.get(10, TimeUnit.SECONDS)
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
            run.cancel()
            releaseBom.complete(Unit)
            service.cancelActiveRequests()
            run.join()
            assertTrue(run.isCancelled)
            assertEquals("Cancelled", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, ""))
            assertEquals("BOM", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_PHASE, ""))
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
            assertEquals("", preferences.getString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, ""))
        }
    }

    @Test fun bomEditDuringExchangeKeepsWholeRunPartialUntilNextSync(): Unit = runBlocking {
        val file = File(context.filesDir, "bom-presets.json")
        val store = BomPresetStore(file)
        val sheet = BomSheet("BOM", 0)
        val preset = BomPreset("preset", "Board", BomParseResult("Board", 1, listOf(sheet), sheet, emptyList(), "source"))
        var confirmedSnapshot = org.json.JSONArray()
        withServer(cancelBom = false, bomReply = {
            // The request already contains the old snapshot. Keep the newer local version pending.
            store.save(preset.copy(name = "Edited while BOM request was in flight"))
            JSONObject().put("bom_preset_protocol", 1).put("presets", confirmedSnapshot).toString()
        }) { service, settings, _, _ ->
            store.save(preset)
            confirmedSnapshot = store.syncSnapshot()
            val result = service.runSync { settings }
            assertFalse(result.isSuccess)
            assertEquals("Partial", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, ""))
            assertEquals(0 to 1, service.pendingSummary())
            assertEquals("Edited while BOM request was in flight", store.list().single().name)
            assertTrue(preferences.getString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, "").orEmpty().isNotBlank())
            assertTrue(InventoryPhotoSync(context).isCurrentSyncComplete())
        }
    }

    @Test fun readingStatusAndOpeningAnotherRepositoryNeverInterruptsAnActiveRun() {
        preferences.edit().clear().commit()
        val repository = InventoryRepository(context)
        preferences.edit().putString(InventoryPreferenceKeys.SYNC_RUN_STATE, "Running")
            .putString(InventoryPreferenceKeys.SYNC_RUN_PHASE, "Inventory").commit()
        assertEquals("Running", repository.loadSyncConfiguration().syncState)
        assertEquals("Running", InventoryRepository(context).loadSyncConfiguration().syncState)
        assertEquals("Inventory", repository.loadSyncConfiguration().syncPhase)
    }

    @Test fun bomPendingUsesConfirmedVersionsAndPreservesNewLocalEditAndTombstone() {
        preferences.edit().clear().commit()
        val file = File(context.filesDir, "bom-presets.json")
        file.delete()
        val store = BomPresetStore(file)
        val sheet = BomSheet("BOM", 0)
        val preset = BomPreset("preset", "Board", BomParseResult("Board", 1, listOf(sheet), sheet, emptyList(), "source"))
        store.save(preset)
        val snapshot = store.syncSnapshot().getJSONObject(0)
        preferences.edit().putString(InventoryPreferenceKeys.BOM_ACK_VERSIONS,
            JSONObject().put(snapshot.getString("id"), snapshot.getString("updated_at")).toString()).commit()
        val helper = InventoryDatabaseHelper(context)
        try {
            val service = InventorySyncService(context, helper, preferences, Any())
            assertEquals(0, service.pendingSummary().second)
            store.save(preset.copy(name = "Edited during request"))
            assertEquals(1, service.pendingSummary().second)
            store.delete(preset.id)
            assertEquals(1, service.pendingSummary().second)
        } finally { helper.close(); file.delete() }
    }

    private suspend fun withServer(cancelBom: Boolean, bomReply: (() -> String)? = null,
        block: suspend (InventorySyncService, SyncConfiguration, CompletableFuture<Unit>, CompletableFuture<Unit>) -> Unit) {
        preferences.edit().clear().commit()
        File(context.filesDir, "bom-presets.json").delete()
        val helper = InventoryDatabaseHelper(context)
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val settings = SyncConfiguration("test", "http://127.0.0.1:${server.localPort}", "key", false, "", "")
            preferences.edit().putString(InventoryPreferenceKeys.SERVER_BASE_URL, settings.serverBaseUrl)
                .putString(InventoryPreferenceKeys.API_TOKEN, settings.apiToken).commit()
            val bomStarted = CompletableFuture<Unit>()
            val releaseBom = CompletableFuture<Unit>()
            val responder = CompletableFuture.runAsync {
                repeat(if (bomReply != null) 6 else 5) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        repeat(length) { reader.read() }
                        val body = when (index) {
                            0 -> JSONObject().put("inventory_protocol", 1).put("bom_preset_protocol", 1)
                                .put("photo_protocol", if (bomReply != null) 1 else 0).toString()
                            1 -> "{\"server_id\":\"server\",\"account_id\":\"account\",\"role\":\"admin\"}"
                            2 -> "{}"
                            3 -> "{\"inventory_protocol\":1,\"sync_cursor\":1,\"server_time\":\"$inventoryTime\"}"
                            4 -> {
                                bomStarted.complete(Unit)
                                if (cancelBom) releaseBom.get(10, TimeUnit.SECONDS)
                                bomReply?.invoke() ?: "{\"detail\":\"BOM unavailable\"}"
                            }
                            else -> "{\"photo_protocol\":1,\"photos\":[]}"
                        }
                        val bytes = body.toByteArray()
                        val code = if (index == 4 && bomReply == null) "503 Unavailable" else "200 OK"
                        runCatching {
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                }
            }
            try {
                block(InventorySyncService(context, helper, preferences, Any()), settings, bomStarted, releaseBom)
                responder.get(10, TimeUnit.SECONDS)
            } finally { releaseBom.complete(Unit); helper.close() }
        }
    }
}
