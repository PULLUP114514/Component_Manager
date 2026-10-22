package com.componentvault.android.ui.screen

import com.componentvault.android.model.InventoryScreenUiState

/** Counts and filter shapes only; never retain inventory or search text in feedback. */
internal fun inventoryDiagnosticFields(state: InventoryScreenUiState): List<Pair<String, Any?>> = listOf(
    "total" to state.totalComponentCount,
    "items" to state.list.items.size,
    "query_length" to state.filters.query.length,
    "query_active" to state.filters.query.isNotBlank(),
    "category_active" to !state.filters.category.isNullOrBlank(),
    "location_active" to !state.filters.location.isNullOrBlank(),
    "stock" to state.filters.stockFilter,
)
