package com.componentvault.android.data.bom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BomElectricalAlternativeMatcherTest {
    @Test
    fun matchingUnitsRequireEveryElectricalParameterAndPackage() {
        val capacitor = requirement("C1", "0603", mapOf("Reference" to "C1", "Comment" to "100nF ±10% 16V"))
        val good = candidate("good", "电容", "0603", mapOf("容量" to "0.1uF", "精度" to "10%", "耐压" to "16V"))
        val wrongVoltage = good.copy(inventoryId = "wrong-voltage", parameters = good.parameters + ("耐压" to "10V"))
        val wrongPackage = good.copy(inventoryId = "wrong-package", packageName = "0805")
        assertEquals(listOf("good"), BomElectricalAlternativeMatcher.suggest(
            capacitor, listOf(good, wrongVoltage, wrongPackage),
        ).candidates.map { it.inventoryId })

        val resistor = requirement("R1", "0603", mapOf(
            "Reference" to "R1", "阻值" to "4.7kΩ", "精度" to "1%", "功率" to "0.1W",
        ))
        val resistorStock = candidate("resistor", "Resistor", "0603", mapOf(
            "Resistance" to "4700Ω", "Tolerance" to "1%", "Power" to "100mW",
        ))
        assertEquals(listOf("resistor"), BomElectricalAlternativeMatcher.suggest(
            resistor, listOf(resistorStock, good),
        ).candidates.map { it.inventoryId })
    }

    @Test
    fun missingCurrentExplainsWhyInductorHasNoSuggestion() {
        val inductor = requirement("L1", "0603", mapOf(
            "Reference" to "L1", "电感量" to "10uH", "精度" to "5%",
        ))
        val stock = candidate("maybe", "电感", "0603", mapOf(
            "电感量" to "10uH", "精度" to "5%", "额定电流" to "1A",
        ))
        val result = BomElectricalAlternativeMatcher.suggest(inductor, listOf(stock))
        assertTrue(result.candidates.isEmpty())
        assertTrue("额定电流" in result.explanation)
    }

    @Test
    fun milliohmIsNotMegohmAndInvalidFractionsDoNotCrash() {
        val row = requirement("R1", "0603", mapOf(
            "Reference" to "R1", "阻值" to "1mΩ", "精度" to "1%", "功率" to "0.1W",
        ))
        val milli = candidate("milli", "电阻", "0603", mapOf(
            "阻值" to "0.001Ω", "精度" to "1%", "功率" to "100mW",
        ))
        val mega = milli.copy(inventoryId = "mega", parameters = milli.parameters + ("阻值" to "1MΩ"))
        assertEquals(listOf("milli"), BomElectricalAlternativeMatcher.suggest(row, listOf(milli, mega)).candidates.map { it.inventoryId })
        val thirdWatt = row.copy(sourceRows = listOf(BomSourceRow("BOM", 2, 1, row.sourceRows[0].fields + ("功率" to "1/3W"))))
        assertTrue(BomElectricalAlternativeMatcher.suggest(thirdWatt, listOf(milli)).candidates.isEmpty())
    }

    @Test
    fun unknownElectricalFieldBlocksSuggestion() {
        val row = requirement("C1", "0603", mapOf(
            "Reference" to "C1", "容量" to "100nF", "精度" to "10%", "耐压" to "16V", "ESR" to "10mΩ",
        ))
        val stock = candidate("stock", "电容", "0603", mapOf(
            "容量" to "0.1uF", "精度" to "10%", "耐压" to "16V",
        ))
        assertTrue(BomElectricalAlternativeMatcher.suggest(row, listOf(stock)).candidates.isEmpty())
    }
    private fun requirement(reference: String, packageName: String, fields: Map<String, String>) = BomRequirement(
        identity = BomRequirementIdentity(reference), sku = null, name = null, model = null,
        packageName = packageName, quantityPerSet = 1, requiredQuantity = 1,
        sourceRows = listOf(BomSourceRow("BOM", 2, 1, fields)),
    )

    private fun candidate(id: String, category: String, packageName: String, parameters: Map<String, String>) =
        InventoryMatchCandidate(id, id, packageName = packageName, category = category, parameters = parameters)
}
