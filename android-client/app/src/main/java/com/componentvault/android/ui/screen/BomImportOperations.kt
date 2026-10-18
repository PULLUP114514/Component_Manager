package com.componentvault.android.ui.screen

import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.data.bom.BomColumnMapping
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomParser
import com.componentvault.android.data.bom.BomReleasePreview
import com.componentvault.android.data.bom.BomSheet
import com.componentvault.android.data.bom.BomTableInspection
import com.componentvault.android.data.bom.ComponentHubDuplicatePolicy
import com.componentvault.android.data.bom.ComponentHubParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Parses import files and asks the repository to build a read-only import preview. */
internal class BomImportOperations(private val repository: InventoryRepository) {
    suspend fun listSheets(bytes: ByteArray): List<BomSheet> = withContext(Dispatchers.Default) {
        BomParser.listXlsxSheets(bytes)
    }

    suspend fun inspect(bytes: ByteArray, fileName: String, sheetName: String?): BomTableInspection =
        withContext(Dispatchers.Default) {
            if (fileName.endsWith(".xlsx", true)) BomParser.inspectXlsx(bytes, sheetName)
            else BomParser.inspectCsv(bytes)
        }

    suspend fun preview(
        bytes: ByteArray,
        fileName: String,
        projectName: String,
        productionSets: Int,
        sheetName: String?,
        mapping: BomColumnMapping?,
        selections: Map<String, String>,
        searchQueries: Map<String, String>,
        excludedKeys: Set<String>,
    ): BomReleasePreview {
        val parsed = withContext(Dispatchers.Default) {
            if (fileName.endsWith(".xlsx", true)) {
                BomParser.parseXlsx(bytes, projectName, productionSets, sheetName, mapping)
            } else {
                require(fileName.endsWith(".csv", true)) { "仅支持 CSV 或 XLSX BOM。" }
                BomParser.parseCsv(bytes, projectName, productionSets, mapping)
            }
        }
        return previewPreset(parsed, selections, searchQueries, excludedKeys)
    }

    suspend fun previewPreset(
        parsed: BomParseResult,
        selections: Map<String, String>,
        searchQueries: Map<String, String>,
        excludedKeys: Set<String>,
    ): BomReleasePreview = repository.previewBomRelease(parsed, selections, searchQueries, excludedKeys)

    suspend fun previewComponentHub(bytes: ByteArray, skipDuplicates: Boolean): ComponentHubParseResult =
        repository.previewComponentHub(
            bytes,
            if (skipDuplicates) ComponentHubDuplicatePolicy.SKIP else ComponentHubDuplicatePolicy.BLOCK,
        )
}
