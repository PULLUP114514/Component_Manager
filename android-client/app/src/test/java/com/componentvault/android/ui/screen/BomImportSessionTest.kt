package com.componentvault.android.ui.screen

import android.app.Application
import com.componentvault.android.data.InventoryDatabaseHelper
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomRequirement
import com.componentvault.android.data.bom.BomRequirementIdentity
import com.componentvault.android.data.bom.BomSheet
import com.componentvault.android.model.ComponentDraft
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BomImportSessionTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before fun setUp() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        repository = InventoryRepository(context)
    }

    @Test fun clearedMatchStaysUnmatchedWhileAnotherAutomaticMatchRemains() = runBlocking {
        for (sku in listOf("R-1", "C-1")) {
            val saved = repository.saveComponent(ComponentDraft(
                sku = sku, name = sku, category = "Passive", packageName = "0603",
                location = "A", description = "", quantity = 8, minStock = 0,
            ))
            assertTrue(saved.isSuccess, saved.message)
        }
        val requirements = listOf("R-1", "C-1", "M-1").map { sku ->
            BomRequirement(
                identity = BomRequirementIdentity("sku:$sku"), sku = sku, name = sku,
                model = null, packageName = "0603", quantityPerSet = 1, requiredQuantity = 1,
                sourceRows = emptyList(),
            )
        }
        val parsed = BomParseResult(
            projectName = "Board", productionSets = 1,
            availableSheets = listOf(BomSheet("BOM", 0)), selectedSheet = BomSheet("BOM", 0),
            requirements = requirements, fileSha256 = "test-file",
        )
        val matched = repository.previewBomRelease(parsed)
        assertNotNull(matched.matchingLines.first { it.requirement.sku == "R-1" }.componentId)
        assertNotNull(matched.matchingLines.first { it.requirement.sku == "C-1" }.componentId)

        val cleared = repository.previewBomRelease(
            parsed,
            selections = clearBomSelections(emptyMap(), listOf(requirements.first().identity.canonicalKey)),
        )
        assertNull(cleared.matchingLines.first { it.requirement.sku == "R-1" }.componentId)
        assertTrue(cleared.matchingLines.first { it.requirement.sku == "C-1" }.componentId ==
            matched.matchingLines.first { it.requirement.sku == "C-1" }.componentId)
        val withoutExcluded = repository.previewBomRelease(
            parsed,
            selections = clearBomSelections(emptyMap(), listOf(requirements.first().identity.canonicalKey)),
            excludedKeys = setOf(requirements.last().identity.canonicalKey),
        )
        assertEquals(listOf("R-1"), bomProcurementEligible(withoutExcluded).map { it.sku })
    }
}
