package com.componentvault.android.data.bom

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BomProcurementStoreTest {
    @Test
    fun repeatedMarkIsIdempotentAndArrivalNeedsAnExplicitNewMark() = withStore { store, _ ->
        val requirement = requirement("sku:123", "C123", "Model", "SMD")
        val first = store.markAll("hash:Board", "Board", listOf(requirement, requirement))
        assertEquals(1, first.size)
        assertEquals(first.single(), store.mark("hash:Board", "Board", requirement))
        assertEquals(1, store.list().size)
        assertTrue(store.matchInbound("C123", null, null).single().status == BomProcurementStatus.ArrivedNeedsReview)
        assertTrue(store.matchInbound("C123", null, null).isEmpty())
        assertEquals(BomProcurementStatus.InTransit, store.mark("hash:Board", "Board", requirement).status)
    }

    @Test
    fun skuNeverFallsBackAndSkuLessModelPackageSurvivesReload() = withStore { store, file ->
        store.markAll("hash:Board", "Board", listOf(
            requirement("sku:wrong", "C999", "Model", "SMD"),
            requirement("model:exact", null, "Model", "SMD"),
        ))
        val reopened = BomProcurementStore(file)
        assertEquals(0, reopened.matchInbound("C123", "Model", "TH").size)
        val arrived = reopened.matchInbound("C123", "Model", "SMD")
        assertEquals(listOf("model:exact"), arrived.map(BomProcurementEntry::requirementCanonicalKey))
        assertEquals(BomProcurementStatus.InTransit,
            reopened.list().first { it.requirementCanonicalKey == "sku:wrong" }.status)
        assertFalse(reopened.remove("hash:Board", "missing"))
        assertTrue(reopened.remove("hash:Board", "model:exact"))
    }

    private fun requirement(key: String, sku: String?, model: String, packageName: String) =
        BomRequirement(BomRequirementIdentity(key), sku, model, model, packageName, 1, 1, emptyList())

    private fun withStore(block: (BomProcurementStore, File) -> Unit) {
        val directory = Files.createTempDirectory("bom-procurement-test").toFile()
        try {
            val file = File(directory, "bom-procurement.json")
            block(BomProcurementStore(file), file)
        } finally { directory.deleteRecursively() }
    }
}
