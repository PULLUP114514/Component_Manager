package com.componentvault.android.ui.screen

import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.InventoryFiltersUiState
import com.componentvault.android.model.InventoryStockFilter
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
}
