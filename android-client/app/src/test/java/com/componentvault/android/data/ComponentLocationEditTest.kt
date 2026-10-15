package com.componentvault.android.data

import android.app.Application
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.MovementEntryDraft
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ComponentLocationEditTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before
    fun setUp() {
        val helper = InventoryDatabaseHelper(context)
        try {
            context.deleteDatabase(helper.databaseName)
        } finally {
            helper.close()
        }
        repository = InventoryRepository(context)
    }

    @Test
    fun editingLocationMovesAllAllocationsAndKeepsTotal() = runBlocking {
        val draft = ComponentDraft(
            sku = "LOCATION-EDIT", name = "Part", category = "IC", packageName = "SOT-23",
            location = "A", description = "", quantity = 10, minStock = 0,
        )
        val created = repository.saveComponent(draft)
        assertTrue(created.isSuccess, created.message)
        val componentId = requireNotNull(created.entityId)
        assertTrue(repository.recordMovement(MovementEntryDraft(
            componentId = componentId, movementType = "transfer", quantity = 4,
            reason = "Split", note = "", locationId = "A", destinationLocationId = "B",
        )).isSuccess)
        assertEquals(mapOf("A" to 6, "B" to 4), allocations(componentId).filterValues { it > 0 })

        val edited = repository.saveComponent(draft.copy(id = componentId, location = "C"))

        assertTrue(edited.isSuccess, edited.message)
        assertEquals(mapOf("A" to 0, "B" to 0, "C" to 10), allocations(componentId))
        assertEquals(10, repository.loadComponents().single { it.id == componentId }.quantity)
    }

    @Test
    fun editingTotalWithoutChangingLocationPreservesOtherAllocation() = runBlocking {
        val draft = ComponentDraft(
            sku = "QUANTITY-EDIT", name = "Part", category = "IC", packageName = "SOT-23",
            location = "A", description = "", quantity = 10, minStock = 0,
        )
        val created = repository.saveComponent(draft)
        assertTrue(created.isSuccess, created.message)
        val componentId = requireNotNull(created.entityId)
        assertTrue(repository.recordMovement(MovementEntryDraft(
            componentId = componentId, movementType = "transfer", quantity = 4,
            reason = "Split", note = "", locationId = "A", destinationLocationId = "B",
        )).isSuccess)

        val edited = repository.saveComponent(draft.copy(id = componentId, location = "A", quantity = 12))

        assertTrue(edited.isSuccess, edited.message)
        assertEquals(mapOf("A" to 8, "B" to 4), allocations(componentId))
    }

    private fun allocations(componentId: String): Map<String, Int> {
        val helper = InventoryDatabaseHelper(context)
        try {
            return helper.readableDatabase.rawQuery(
                "SELECT location_id, quantity FROM component_allocations WHERE component_id = ?",
                arrayOf(componentId),
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
                }
            }
        } finally {
            helper.close()
        }
    }
}
