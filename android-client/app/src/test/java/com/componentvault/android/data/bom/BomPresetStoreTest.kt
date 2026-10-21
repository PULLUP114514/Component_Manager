package com.componentvault.android.data.bom

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BomPresetStoreTest {
    @Test
    fun savesCompleteSourceAndChoicesThenReconfiguresSets() = withStore { store, _ ->
        val preset = samplePreset()
        assertTrue(store.list().isEmpty())

        assertEquals(preset, store.save(preset))
        val restored = store.list().single()
        assertEquals(preset, restored)
        assertEquals("Test-Point", restored.parsed.requirements.single().name)
        assertEquals("TP1", restored.parsed.requirements.single().sourceRows.single().fields["Designator"])
        assertEquals(mapOf("row:BOM:2" to "inventory-1"), restored.selections)
        assertEquals(setOf("row:BOM:3"), restored.excludedKeys)

        val resized = restored.reconfigure(5)
        assertEquals(5, resized.parsed.productionSets)
        assertEquals(15, resized.parsed.requirements.single().requiredQuantity)
        assertEquals(3, resized.parsed.requirements.single().quantityPerSet)
        assertEquals(6, restored.parsed.requirements.single().requiredQuantity)
        assertEquals(restored.selections, resized.selections)
    }

    @Test
    fun sameNameOverwritesWithStableIdAndDeleteIsExplicit() = withStore { store, _ ->
        store.save(samplePreset())
        val replaced = store.save(samplePreset().copy(id = "new-id", name = "board", selections = emptyMap()))
        assertEquals("preset-1", replaced.id)
        assertEquals(1, store.list().size)
        assertTrue(store.list().single().selections.isEmpty())
        assertFalse(store.delete("missing"))
        assertTrue(store.delete("preset-1"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun malformedFileReportsAnActionableErrorWithoutOverwritingIt() = withStore { store, file ->
        file.writeText("{broken")
        val original = file.readText()
        val error = assertFailsWith<BomPresetStoreException> { store.list() }
        assertTrue(error.message.orEmpty().contains("备份"))
        assertFailsWith<BomPresetStoreException> { store.save(samplePreset()) }
        assertEquals(original, file.readText())
    }

    @Test
    fun sharedWirePreservesEmptyChoiceMetadataAndImmutableLineIds() {
        val preset = samplePreset().let { source -> source.copy(
            parsed = source.parsed.copy(sourceName = "Windows.xlsx", warnings = listOf("人工检查")),
            selections = mapOf(source.parsed.requirements.single().identity.canonicalKey to ""),
            excludedKeys = setOf(source.parsed.requirements.single().identity.canonicalKey),
        ) }
        assertEquals(preset, BomPresetCodec.decode(BomPresetCodec.encode(listOf(preset))).single())
    }

    @Test
    fun remoteMergeKeepsSameNamedIdsAndDeletionTombstones() = withStore { store, _ ->
        val first = samplePreset().copy(excludedKeys = emptySet())
        store.save(first)
        val sent = store.syncSnapshot()
        val second = BomPresetCodec.encodeRecord(first.copy(id = "remote-id"), "2099-01-01T00:00:00Z")
        store.mergeRemote(org.json.JSONArray().put(second), sent)
        assertEquals(setOf(first.id, "remote-id"), store.list().map { it.id }.toSet())
        store.delete("remote-id")
        assertEquals(listOf(first.id), store.list().map { it.id })
        assertTrue((0 until store.syncSnapshot().length()).any {
            val record = store.syncSnapshot().getJSONObject(it)
            record.getString("id") == "remote-id" && record.getBoolean("deleted")
        })
    }

    @Test
    fun equalServerTimestampDoesNotOverwriteEditAfterSnapshot() = withStore { store, _ ->
        val original = samplePreset().copy(excludedKeys = emptySet())
        store.save(original)
        val sent = store.syncSnapshot()
        store.save(original.copy(name = "Edited"))
        val current = store.syncSnapshot().getJSONObject(0)
        val incoming = BomPresetCodec.encodeRecord(original, current.getString("updated_at"))
        store.mergeRemote(org.json.JSONArray().put(incoming), sent)
        assertEquals("Edited", store.list().single().name)
    }
    @Test
    fun futureRemoteTimestampIsAdvancedByLocalEditAndNewerResponseCannotErasePendingEdit() = withStore { store, _ ->
        val original = samplePreset().copy(excludedKeys = emptySet())
        val remote = org.json.JSONArray().put(BomPresetCodec.encodeRecord(original, "2099-01-01T00:00:00Z"))
        store.mergeRemote(remote, org.json.JSONArray())
        val outbound = store.syncSnapshot()
        store.save(original.copy(name = "Local edit"))
        assertTrue(java.time.Instant.parse(store.syncSnapshot().getJSONObject(0).getString("updated_at")) > java.time.Instant.parse("2099-01-01T00:00:00Z"))
        store.mergeRemote(org.json.JSONArray().put(BomPresetCodec.encodeRecord(original, "2100-01-01T00:00:00Z")), outbound)
        assertEquals("Local edit", store.list().single().name)
    }

    @Test
    fun readsSharedCrossPlatformFixtureWithoutMultiplyingSourceRows() {
        val fixture = sequenceOf(File("../test-data/bom-preset-v1.json"), File("../../test-data/bom-preset-v1.json"), File("test-data/bom-preset-v1.json"))
            .first { it.exists() }
        val preset = BomPresetCodec.decode(fixture.readText(Charsets.UTF_8)).single()
        assertEquals(3, preset.parsed.requirements.size)
        assertEquals(3, preset.parsed.requirements.first().quantityPerSet)
        assertEquals(6, preset.parsed.requirements.first().requiredQuantity)
        assertEquals(2, preset.parsed.requirements.first().sourceRows.size)
        assertEquals("", preset.selections["original:unmatched:2"])
        assertTrue("original:excluded:3" in preset.excludedKeys)
        assertEquals(preset, BomPresetCodec.decode(BomPresetCodec.encode(listOf(preset))).single())
    }

    @Test
    fun migratesLegacyFileWithBackupAndKeepsOrphanChoicesLocally() = withStore { store, file ->
        val legacy = """{"version":1,"presets":[{"id":"legacy","name":"Legacy","parsed":{"projectName":"Legacy","productionSets":1,"availableSheets":[{"name":"BOM","index":0,"hidden":false}],"selectedSheet":{"name":"BOM","index":0,"hidden":false},"fileSha256":"hash","requirements":[{"identity":"row:old","sku":"SKU","name":null,"model":null,"packageName":null,"quantityPerSet":2,"requiredQuantity":2,"sourceRows":[{"sheetName":"BOM","rowNumber":2,"quantityPerSet":2,"fields":{"Reference":"R1,R2"}}]}]},"selections":{"row:old":"","orphan":"old-id"},"excludedKeys":["orphan"]}]}"""
        file.writeText(legacy)
        val preset = store.list().single()
        assertEquals(mapOf("row:old" to "", "orphan" to "old-id"), preset.selections)
        assertEquals(setOf("orphan"), preset.excludedKeys)
        assertEquals(legacy, File(file.parentFile, "${file.name}.v1.bak").readText())
        assertEquals(2, org.json.JSONObject(file.readText()).getInt("version"))
        val outbound = store.syncSnapshot()
        assertFalse(outbound.getJSONObject(0).has("_android_legacy_choices"))
        store.mergeRemote(outbound, outbound)
        assertEquals(preset, store.list().single())
    }
    @Test
    fun editsAndDeletionStayNewerAfterServerMicrosecondNormalization() = withStore { store, _ ->
        val original = samplePreset().copy(excludedKeys = emptySet())
        val prior = "2099-01-01T00:00:00.123456789Z"
        store.mergeRemote(org.json.JSONArray().put(BomPresetCodec.encodeRecord(original, prior)), org.json.JSONArray())
        fun serverTime(json: String): java.time.Instant = java.time.Instant.parse(
            org.json.JSONArray(json).getJSONObject(0).getString("updated_at"),
        ).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val remoteTime = java.time.Instant.parse(prior).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        store.save(original.copy(name = "First edit"))
        val first = serverTime(store.syncSnapshot().toString())
        assertTrue(first > remoteTime)
        assertTrue(first >= remoteTime.plusMillis(1))
        store.save(original.copy(name = "Second edit"))
        val second = serverTime(store.syncSnapshot().toString())
        assertTrue(second >= first.plusMillis(1))
        store.delete(original.id)
        assertTrue(serverTime(store.syncSnapshot().toString()) >= second.plusMillis(1))
    }
    private fun samplePreset(): BomPreset {
        val sheet = BomSheet("BOM", 0)
        val source = BomSourceRow("BOM", 2, 3, mapOf("Comment" to "Test-Point", "Designator" to "TP1"))
        val requirement = BomRequirement(
            BomRequirementIdentity("row:BOM:2"), null, "Test-Point", null, "TP",
            3, 6, listOf(source),
        )
        return BomPreset(
            id = "preset-1",
            name = "Board",
            parsed = BomParseResult("Board", 2, listOf(sheet), sheet, listOf(requirement), "source-hash"),
            selections = mapOf("row:BOM:2" to "inventory-1"),
            excludedKeys = setOf("row:BOM:3"),
        )
    }

    private fun withStore(block: (BomPresetStore, File) -> Unit) {
        val directory = Files.createTempDirectory("bom-preset-test").toFile()
        try {
            val file = File(directory, "bom-presets.json")
            block(BomPresetStore(file), file)
        } finally {
            directory.deleteRecursively()
        }
    }
}
