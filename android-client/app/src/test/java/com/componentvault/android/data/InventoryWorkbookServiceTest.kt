package com.componentvault.android.data

import android.app.Application
import com.componentvault.android.model.StorageLocationSaveIntent
import kotlinx.coroutines.runBlocking
import org.junit.Before
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
class InventoryWorkbookServiceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before fun setUp() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        repository = InventoryRepository(context)
    }

    private fun workbook() = InventoryWorkbook(
        source = WorkbookSource.ComponentVault,
        components = listOf(WorkbookComponent("backup-part", "TEST-BACKUP", "Backup part", "IC", "SOT-23", "backup-bin", "", 4, 1, "2026-10-18T00:00:00.000Z", false, null)),
        locations = listOf(WorkbookLocation("backup-bin", "Backup shelf", "2026-10-18T00:00:00.000Z", false)),
        allocations = listOf(WorkbookAllocation("backup-part", "backup-bin", 4)),
        movements = listOf(WorkbookMovement("backup-movement", "backup-part", "inbound", 4, "Initial", "", "2026-10-18T00:00:00.000Z", "2026-10-18T00:00:00.000Z", false, "backup-bin", null)),
        fingerprint = "",
        warnings = emptyList(),
    )

    @Test fun restorePreservesTablesAndRejectsReplay() = runBlocking {
        val source = workbook()
        val preview = repository.previewInventoryWorkbook(InventoryWorkbookWriter.write(source))
        assertEquals(1, preview.newComponentCount)
        val restored = repository.restoreInventoryWorkbook(preview)
        assertTrue(restored.isSuccess, restored.message)
        val exported = InventoryWorkbookCodec.parse(repository.exportInventoryWorkbook())
        assertEquals(source.components, exported.components)
        assertEquals(source.allocations, exported.allocations)
        assertEquals(source.movements, exported.movements)
        assertTrue(exported.locations.containsAll(source.locations))
        assertFalse(repository.restoreInventoryWorkbook(preview).isSuccess)
        assertEquals(4, repository.loadComponents().single().quantity)
        assertEquals(1, repository.loadMovements().size)
    }

    @Test fun deletedDestinationAfterPreviewRejectsRestoreWithoutPartialStock() = runBlocking {
        assertTrue(repository.saveStorageLocation("backup-bin", "Backup shelf", StorageLocationSaveIntent.Create).isSuccess)
        val preview = repository.previewInventoryWorkbook(InventoryWorkbookWriter.write(workbook()))
        assertTrue(repository.deleteStorageLocation("backup-bin").isSuccess)
        assertFalse(repository.restoreInventoryWorkbook(preview).isSuccess)
        assertTrue(repository.loadComponents().isEmpty())
        assertTrue(repository.loadMovements().isEmpty())
        assertTrue(repository.loadAllocations().isEmpty())
    }
}
