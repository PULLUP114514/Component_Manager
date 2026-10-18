package com.componentvault.android.ui.screen

import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.InventoryFiltersUiState
import com.componentvault.android.model.InventoryStockFilter
import com.componentvault.android.model.StorageLocationRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InventoryStateProjectorTest {
    private val stocked = ComponentRecord(
        id = "stocked", sku = "C1", name = "Stocked", category = "IC",
        packageName = "SOT-23", location = "A", description = "", quantity = 8,
        minStock = 1, updatedAt = "2026-10-18", deleted = false,
    )
    private val low = stocked.copy(id = "low", sku = "C2", name = "Low", quantity = 1)

    @Test
    fun savedComponentCanBeRevealedWhenCurrentFiltersHideIt() {
        val projector = InventoryStateProjector(
            components = listOf(stocked, low), movements = emptyList(),
            issuedQuantities = mapOf(stocked.id to 3L), locations = emptyList(),
            allocations = emptyList(),
        )
        val lowStockOnly = InventoryFiltersUiState(stockFilter = InventoryStockFilter.LowStock)
        val hidden = projector.inventory(lowStockOnly, stocked.id)
        assertEquals(listOf(low.id), hidden.list.items.map { it.id })
        assertNull(hidden.list.selectedComponentId)

        val revealedFilters = projector.filtersRevealing(stocked.id, lowStockOnly)
        val revealed = projector.inventory(revealedFilters, stocked.id)
        assertEquals(InventoryStockFilter.All, revealedFilters.stockFilter)
        assertEquals(stocked.id, revealed.list.selectedComponentId)
        assertEquals(3L, revealed.detail.issuedQuantity)
    }

    @Test
    fun locationFilterIncludesStockInSecondaryAllocationAndEmptyLocationsRemainSelectable() {
        val projector = InventoryStateProjector(
            components = listOf(stocked, low), movements = emptyList(),
            issuedQuantities = emptyMap(),
            locations = listOf(
                StorageLocationRecord("A", "Drawer A", "2026-10-18"),
                StorageLocationRecord("B", "Drawer B", "2026-10-18"),
                StorageLocationRecord("C", "Empty drawer", "2026-10-18"),
            ),
            allocations = listOf(
                ComponentAllocationRecord("stocked", "A", "Drawer A", 3),
                ComponentAllocationRecord("stocked", "B", "Drawer B", 5),
                ComponentAllocationRecord("low", "A", "Drawer A", 1),
            ),
        )

        assertEquals(listOf("stocked"), projector.inventory(
            InventoryFiltersUiState(location = "B"), null,
        ).list.items.map { it.id })
        assertEquals(emptyList(), projector.inventory(
            InventoryFiltersUiState(location = "C"), null,
        ).list.items.map { it.id })
        assertEquals(listOf("A", "B", "C"), projector.inventory(
            InventoryFiltersUiState(), null,
        ).availableLocations)
    }

    @Test
    fun depletedComponentRemainsInItsHomeLocationButOldZeroAllocationDoesNotMatch() {
        val depleted = stocked.copy(id = "depleted", sku = "C3", quantity = 0)
        val moved = stocked.copy(id = "moved", sku = "C4", quantity = 5)
        val deleted = stocked.copy(id = "deleted", sku = "C5", deleted = true)
        val projector = InventoryStateProjector(
            components = listOf(depleted, moved, deleted), movements = emptyList(),
            issuedQuantities = emptyMap(),
            locations = listOf(
                StorageLocationRecord("A", "Drawer A", "2026-10-18"),
                StorageLocationRecord("B", "Drawer B", "2026-10-18"),
            ),
            allocations = listOf(
                ComponentAllocationRecord("depleted", "A", "Drawer A", 0),
                ComponentAllocationRecord("moved", "A", "Drawer A", 0),
                ComponentAllocationRecord("moved", "B", "Drawer B", 5),
                ComponentAllocationRecord("deleted", "D", "Old drawer", 8),
            ),
        )

        assertEquals(listOf("depleted"), projector.inventory(
            InventoryFiltersUiState(location = "A"), null,
        ).list.items.map { it.id })
        assertEquals(listOf("moved"), projector.inventory(
            InventoryFiltersUiState(location = "B"), null,
        ).list.items.map { it.id })
        assertEquals(listOf("A", "B"), projector.inventory(
            InventoryFiltersUiState(), null,
        ).availableLocations)
    }
}
