package com.componentvault.android.ui.screen

import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.DashboardSnapshot
import com.componentvault.android.model.InventoryDetailUiState
import com.componentvault.android.model.InventoryFiltersUiState
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.InventoryListUiState
import com.componentvault.android.model.InventoryScreenUiState
import com.componentvault.android.model.InventorySortOption
import com.componentvault.android.model.InventoryStockFilter
import com.componentvault.android.model.OverviewUiState
import com.componentvault.android.model.StockMovementRecord
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.officialRclSpecificationSummary

/** Builds the inventory and overview screens from one loaded repository snapshot. */
internal class InventoryStateProjector(
    private val components: List<ComponentRecord>,
    private val movements: List<StockMovementRecord>,
    private val issuedQuantities: Map<String, Long>,
    private val locations: List<StorageLocationRecord>,
    private val allocations: List<ComponentAllocationRecord>,
) {
    fun overview(snapshot: DashboardSnapshot): OverviewUiState = OverviewUiState(
        componentCount = snapshot.componentCount,
        totalUnits = snapshot.totalUnits,
        lowStockCount = snapshot.lowStockCount,
        movementCount = snapshot.movementCount,
        lowStockItems = components.filter { it.isLowStock }
            .sortedWith(compareBy<ComponentRecord> { it.quantity }.thenBy { it.name.lowercase() })
            .take(6)
            .map(::listItem),
        recentMovements = movements.take(8),
    )

    fun inventory(
        filters: InventoryFiltersUiState,
        selectedComponentId: String?,
    ): InventoryScreenUiState {
        val filtered = filter(filters)
        val selected = filtered.firstOrNull { it.id == selectedComponentId }
        return InventoryScreenUiState(
            totalComponentCount = components.count { !it.deleted },
            filters = filters,
            availableCategories = CategoryFilterSemantics.options(components.map { it.category }),
            availableLocations = components.map { it.location }.filter { it.isNotBlank() }
                .distinct().sortedBy { it.lowercase() },
            list = InventoryListUiState(
                items = filtered.map(::listItem),
                selectedComponentId = selected?.id,
            ),
            detail = detail(selected),
            storageLocations = locations,
            allocations = allocations,
        )
    }

    fun detail(component: ComponentRecord?): InventoryDetailUiState = InventoryDetailUiState(
        component = component,
        recentMovements = component?.let { selected ->
            movements.filter { it.componentId == selected.id }.take(5)
        }.orEmpty(),
        issuedQuantity = component?.let { issuedQuantities[it.id] } ?: 0,
        allocations = component?.let { selected ->
            allocations.filter { it.componentId == selected.id }
        }.orEmpty(),
    )

    fun filtersRevealing(componentId: String, current: InventoryFiltersUiState): InventoryFiltersUiState =
        if (filter(current).any { it.id == componentId }) current else current.copy(
            query = "",
            stockFilter = InventoryStockFilter.All,
            category = null,
            location = null,
        )

    private fun filter(filters: InventoryFiltersUiState): List<ComponentRecord> {
        val filtered = components.filter { component ->
            val matchesLowStock = filters.stockFilter != InventoryStockFilter.LowStock || component.isLowStock
            val matchesQuery = InventorySearch.matches(component, filters.query)
            val matchesCategory = filters.category.isNullOrBlank() ||
                CategoryFilterSemantics.sameCategory(component.category, filters.category.orEmpty())
            val matchesLocation = filters.location.isNullOrBlank() || component.location == filters.location
            matchesLowStock && matchesQuery && matchesCategory && matchesLocation
        }
        return when (filters.sort) {
            InventorySortOption.UpdatedNewest -> filtered.sortedWith(
                compareByDescending<ComponentRecord> { it.updatedAt }.thenBy { it.name.lowercase() },
            )
            InventorySortOption.Name -> filtered.sortedBy { it.name.lowercase() }
            InventorySortOption.QuantityLowToHigh -> filtered.sortedWith(
                compareBy<ComponentRecord> { it.quantity }.thenBy { it.name.lowercase() },
            )
            InventorySortOption.QuantityHighToLow -> filtered.sortedWith(
                compareByDescending<ComponentRecord> { it.quantity }.thenBy { it.name.lowercase() },
            )
        }
    }

    private fun listItem(component: ComponentRecord): InventoryListItemUiState = InventoryListItemUiState(
        id = component.id,
        name = component.name,
        sku = component.sku,
        category = component.category,
        packageName = component.packageName,
        location = component.location,
        quantity = component.quantity,
        minStock = component.minStock,
        isLowStock = component.isLowStock,
        updatedAt = component.updatedAt,
        productImageUrl = component.productImageUrl,
        description = component.description,
        specificationSummary = component.officialRclSpecificationSummary(),
        issuedQuantity = issuedQuantities[component.id] ?: 0,
    )
}
