package com.componentvault.android.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InventoryPhotoSyncTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
        }
        output.toByteArray()
    }

    @Test fun downloadedPhotoResolvesForExcelAndClearingPreservesOriginal() {
        val store = InventoryImageStore(context)
        val original = png(Color.RED)
        val downloaded = png(Color.BLUE)
        val marker = store.persist(original)
        val downloadMarker = WorkbookImageCodec.marker(downloaded)
        store.persistDownloaded(downloadMarker.substringAfter('：'), downloaded)
        assertContentEquals(downloaded, store.resolve(downloadMarker))
        assertContentEquals(original, store.resolve(marker))
        store.clearDownloads()
        assertNull(store.resolve(downloadMarker))
        assertContentEquals(original, store.resolve(marker))
        assertEquals(downloadMarker.substringAfter('：'), store.key(downloadMarker))
    }

    @Test fun missingOriginalKeepsReferenceAndNextRunRecoversFromAvailableCopy() {
        val bytes = png(Color.GREEN)
        val marker = WorkbookImageCodec.marker(bytes)
        val helper = InventoryDatabaseHelper(context)
        try {
            helper.writableDatabase.execSQL(
                "INSERT INTO components(id,sku,name,category,package_name,location,description,quantity,min_stock,updated_at,deleted) VALUES('photo-part','photo-part','Photo','','','',?,1,0,'2026-10-22T00:00:00Z',0)",
                arrayOf(marker))
        } finally { helper.close() }
        context.getSharedPreferences("component_vault_sync", android.content.Context.MODE_PRIVATE).edit()
            .putString(InventoryPreferenceKeys.BOUND_SERVER_ID, "server")
            .putString(InventoryPreferenceKeys.BOUND_ACCOUNT_ID, "account").commit()
        val photos = InventoryPhotoSync(context)
        val settings = com.componentvault.android.model.SyncConfiguration("test", "", "", false, "", "")
        fun manifest() = org.json.JSONObject().put("photo_protocol", 1).put("photos", org.json.JSONArray().put(
            org.json.JSONObject().put("key", marker.substringAfter('：')).put("available", false)))
        val first = photos.sync(settings, "server", "account", true, {}, ::manifest)
        assertFalse(first.first)
        assertTrue(photos.keys().contains(marker.substringAfter('：')))
        assertTrue(InventoryPhotoSync(context).summary().contains("缺少原始照片"))
        InventoryImageStore(context).persist(bytes)
        val second = photos.sync(settings, "server", "account", true, {}, {
            manifest().apply { getJSONArray("photos").getJSONObject(0).put("available", true) }
        })
        assertTrue(second.first)
        assertTrue(InventoryPhotoSync(context).summary().contains("照片同步完成"))
    }

    @Test fun clearingDownloadsNotifiesUnifiedStatusAndShowsReferencedPhotoPending() {
        val preferences = context.getSharedPreferences("component_vault_sync", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = InventoryRepository(context)
        preferences.edit()
            .putString(InventoryPreferenceKeys.SERVER_BASE_URL, "https://configured.example")
            .putString(InventoryPreferenceKeys.API_TOKEN, "fixture-key")
            .putString(InventoryPreferenceKeys.BOUND_SERVER_ID, "server")
            .putString(InventoryPreferenceKeys.BOUND_ACCOUNT_ID, "account")
            .putString(InventoryPreferenceKeys.SYNC_RUN_STATE, "Complete").commit()
        val bytes = png(Color.BLUE)
        val marker = WorkbookImageCodec.marker(bytes)
        val key = marker.substringAfter('：')
        val store = InventoryImageStore(context)
        store.persistDownloaded(key, bytes)
        val helper = InventoryDatabaseHelper(context)
        try {
            helper.writableDatabase.execSQL(
                "INSERT INTO components(id,sku,name,category,package_name,location,description,quantity,min_stock,updated_at,deleted) VALUES('cached-photo','cached-photo','Photo','','','',?,1,0,'2026-10-22T00:00:00Z',0)",
                arrayOf(marker))
        } finally { helper.close() }
        context.getSharedPreferences("photo-sync", android.content.Context.MODE_PRIVATE).edit().clear()
            .putString("binding", "server/account").putInt("configuration_generation", 0)
            .putString("state", "complete").putStringSet("ack", setOf(key)).commit()
        val photos = InventoryPhotoSync(context)
        val completedText = context.getString(com.componentvault.android.R.string.sync_run_complete)
        assertTrue(photos.isCurrentSyncComplete())
        assertTrue(repository.loadSyncConfiguration().syncSummary.contains(completedText))
        var notifications = 0
        var observed = repository.loadSyncConfiguration()
        val unsubscribe = repository.observeSyncStatus {
            notifications++
            observed = repository.loadSyncConfiguration()
        }
        try {
            photos.clearDownloads()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(notifications > 0)
            assertFalse(observed.syncSummary.contains(completedText))
            assertTrue(observed.syncSummary.contains(context.getString(com.componentvault.android.R.string.sync_run_pending)))
            assertFalse(photos.isCurrentSyncComplete())
            assertNull(store.read(key))
            assertTrue(photos.summary().contains("待处理 1"))
            val priorNotifications = notifications
            unsubscribe()
            context.getSharedPreferences("photo-sync", android.content.Context.MODE_PRIVATE).edit().putString("state", "complete").commit()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(priorNotifications, notifications)
        } finally { unsubscribe() }
    }

    @Test fun mismatchRejectedAndPruningOnlyRemovesUnreferencedDownloads() {
        val store = InventoryImageStore(context)
        val original = png(Color.RED)
        val downloaded = png(Color.BLUE)
        val marker = store.persist(original)
        val key = WorkbookImageCodec.marker(downloaded).substringAfter('：')
        assertFailsWith<IllegalArgumentException> { store.persistDownloaded(key, original) }
        store.persistDownloaded(key, downloaded)
        store.pruneDownloads(setOf(key))
        assertContentEquals(downloaded, store.read(key))
        store.pruneDownloads(emptySet())
        assertNull(store.read(key))
        assertContentEquals(original, store.resolve(marker))
    }
}
