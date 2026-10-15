package com.componentvault.android.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalComponentSkuTest {
    @Test fun missingSupplierSkuIsUniqueAndExistingValueIsStable() {
        val first = LocalComponentSku.ensure("")
        assertTrue(first.matches(Regex("CV-[0-9A-F]{32}")))
        assertNotEquals(first, LocalComponentSku.ensure(" "))
        assertEquals(first, LocalComponentSku.ensure(first))
        assertEquals("C70565", LocalComponentSku.ensure(" C70565 "))
    }
}