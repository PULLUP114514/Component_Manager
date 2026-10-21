package com.componentvault.android.data.bom

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Source requirements and choices only; loading a preset never releases stock. */
data class BomPreset(
    val id: String,
    val name: String,
    val parsed: BomParseResult,
    val selections: Map<String, String> = emptyMap(),
    val excludedKeys: Set<String> = emptySet(),
) {
    fun reconfigure(productionSets: Int): BomPreset {
        require(productionSets > 0) { "生产套数必须是正整数。" }
        return copy(parsed = parsed.copy(productionSets = productionSets, requirements = parsed.requirements.map {
            val required = try { Math.multiplyExact(it.quantityPerSet, productionSets) }
            catch (error: ArithmeticException) { throw IllegalArgumentException("生产套数过大，BOM 需求数量超过整数范围。", error) }
            it.copy(requiredQuantity = required)
        }))
    }
}

class BomPresetStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Shared v2 envelope. All instances for one file use the same lock. */
class BomPresetStore(private val file: File) {
    private val lock = locks.getOrPut(file.canonicalPath) { Any() }
    fun list(): List<BomPreset> = synchronized(lock) { records().mapNotNull(::activePreset) }

    fun save(preset: BomPreset): BomPreset = synchronized(lock) {
        require(preset.id.isNotBlank() && preset.name.isNotBlank()) { "预设 ID 和名称不能为空。" }
        require(preset.parsed.requirements.size <= LocalImportLimits.MAX_DATA_ROWS) { "预设 BOM 数据行超过 5000 行限制。" }
        val existing = records()
        val prior = existing.firstOrNull { it.getString("id") == preset.id }?.let { if (it.optBoolean("deleted")) preset else activePreset(it) }
            ?: existing.mapNotNull(::activePreset).firstOrNull { it.name.equals(preset.name, true) }
        val saved = preset.copy(id = prior?.id ?: preset.id, name = preset.name.trim())
        val previous = existing.firstOrNull { it.getString("id") == saved.id }
        val record = BomPresetCodec.encodeRecord(saved, nextTimestamp(previous))
        write(existing.filterNot { it.getString("id") == saved.id } + record)
        saved
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val existing = records()
        if (existing.none { it.getString("id") == id && !it.optBoolean("deleted") }) return@synchronized false
        write(existing.map { if (it.getString("id") != id) it else JSONObject()
            .put("id", id).put("name", it.getString("name")).put("updated_at", nextTimestamp(it))
            .put("deleted", true).put("content", JSONObject.NULL) })
        true
    }

    fun syncSnapshot(): JSONArray = synchronized(lock) { JSONArray().apply { records().forEach { put(portableRecord(it)) } } }

    /** Equal server timestamps win unless a local edit happened after the outbound snapshot. */
    fun mergeRemote(remote: JSONArray, outbound: JSONArray) = synchronized(lock) {
        val current = records().associateBy { it.getString("id") }.toMutableMap()
        val sent = outbound.objects().associateBy { it.getString("id") }
        remote.objects().forEach { incoming ->
            BomPresetCodec.validateRecord(incoming)
            val id = incoming.getString("id")
            val local = current[id]
            val localTime = local?.getString("updated_at")?.let(Instant::parse)
            val remoteTime = Instant.parse(incoming.getString("updated_at"))
            val changedSinceSend = local != null && sent[id]?.toString() != portableRecord(local).toString()
            if (!changedSinceSend && (localTime == null || remoteTime >= localTime)) {
                current[id] = JSONObject(incoming.toString()).apply {
                    local?.optJSONObject("_android_legacy_choices")?.let { put("_android_legacy_choices", it) }
                }
            }
        }
        write(current.values.toList())
    }

    private fun nextTimestamp(previous: JSONObject?): String {
        val now = Instant.now()
        val prior = previous?.getString("updated_at")?.let(Instant::parse)
        // The server stores microseconds; advance by a millisecond so ordering survives wire normalization.
        val minimum = prior?.plusMillis(1)
        return (if (minimum != null && now < minimum) minimum else now).toString()
    }

    private fun portableRecord(record: JSONObject): JSONObject = JSONObject(record.toString()).apply { remove("_android_legacy_choices") }

    private fun activePreset(record: JSONObject): BomPreset? =
        if (record.optBoolean("deleted")) null else BomPresetCodec.decodeRecord(record)

    private fun records(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            when (root.getInt("version")) {
                1 -> {
                    val migrated = BomPresetCodec.decodeLegacy(root).map { BomPresetCodec.encodeRecord(it, Instant.now().toString()) }
                    migrated.forEach(BomPresetCodec::validateRecord)
                    write(migrated, backupLegacy = true)
                    migrated
                }
                2 -> root.getJSONArray("presets").objects().onEach(BomPresetCodec::validateRecord)
                else -> error("不支持的 BOM 预设格式版本。")
            }
        } catch (error: Exception) {
            throw BomPresetStoreException("无法读取 BOM 预设 ${file.name}；请检查或备份该文件后重试。", error)
        }
    }

    private fun write(records: List<JSONObject>, backupLegacy: Boolean = false) {
        val parent = file.absoluteFile.parentFile
        if (!parent.exists() && !parent.mkdirs()) throw BomPresetStoreException("无法创建 BOM 预设目录：${parent.path}")
        val temp = File(parent, "${file.name}.tmp")
        try {
            temp.writeText(JSONObject().put("version", 2).put("presets", JSONArray().apply { records.forEach { put(it) } }).toString(), Charsets.UTF_8)
            if (backupLegacy && file.exists()) {
                val backup = File(parent, "${file.name}.v1.bak")
                if (!backup.exists()) file.copyTo(backup)
            }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (error: Exception) { throw BomPresetStoreException("无法保存 BOM 预设；请检查设备存储空间后重试。", error) }
        finally { temp.delete() }
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}

internal object BomPresetCodec {
    fun encode(presets: List<BomPreset>): String = JSONObject().put("version", 2)
        .put("presets", JSONArray().apply { presets.forEach { put(encodeRecord(it, Instant.now().toString())) } }).toString()
    fun decode(text: String): List<BomPreset> {
        val root = JSONObject(text)
        return if (root.getInt("version") == 1) decodeLegacy(root) else {
            require(root.getInt("version") == 2)
            root.getJSONArray("presets").objects().onEach(::validateRecord).filterNot { it.optBoolean("deleted") }.map(::decodeRecord)
        }
    }
    fun validateRecord(record: JSONObject) {
        require(record.getString("id").isNotBlank() && record.getString("name").isNotBlank())
        Instant.parse(record.getString("updated_at"))
        if (!record.getBoolean("deleted")) decodeRecord(record)
    }
    fun encodeRecord(preset: BomPreset, updatedAt: String): JSONObject = JSONObject()
        .put("id", preset.id).put("name", preset.name).put("updated_at", updatedAt).put("deleted", false)
        .put("_android_legacy_choices", JSONObject().apply {
            val ids = preset.parsed.requirements.map { it.identity.canonicalKey }.toSet()
            put("selections", JSONObject(preset.selections.filterKeys { it !in ids }))
            put("excluded_keys", JSONArray(preset.excludedKeys.filterNot { it in ids }))
        })
        .put("content", JSONObject().apply {
            val parsed = preset.parsed
            put("schema_version", 1); put("project_name", parsed.projectName); put("production_sets", parsed.productionSets)
            put("source_name", parsed.sourceName); put("file_sha256", parsed.fileSha256)
            put("warnings", JSONArray(parsed.warnings)); put("available_sheets", JSONArray().apply { parsed.availableSheets.forEach { put(sheet(it)) } })
            put("selected_sheet", sheet(parsed.selectedSheet))
            put("lines", JSONArray().apply { parsed.requirements.forEach { line -> put(JSONObject().apply {
                val id = line.identity.canonicalKey
                put("id", id); put("sku", line.sku ?: JSONObject.NULL); put("name", line.name ?: JSONObject.NULL)
                put("model", line.model ?: JSONObject.NULL); put("package_name", line.packageName ?: JSONObject.NULL)
                put("quantity_per_set", line.quantityPerSet); put("selection", preset.selections[id] ?: JSONObject.NULL)
                put("excluded", id in preset.excludedKeys)
                put("source_rows", JSONArray().apply { line.sourceRows.forEach { put(JSONObject()
                    .put("sheet_name", it.sheetName).put("row_number", it.rowNumber).put("quantity_per_set", it.quantityPerSet).put("fields", JSONObject(it.fields))) } })
            }) } })
        })
    fun decodeRecord(record: JSONObject): BomPreset {
        val content = record.getJSONObject("content")
        require(content.getInt("schema_version") == 1)
        val sets = content.getInt("production_sets"); require(sets > 0)
        val lines = content.getJSONArray("lines").objects()
        require(lines.size <= LocalImportLimits.MAX_DATA_ROWS)
        require(lines.all { it.getString("id").isNotBlank() } && lines.map { it.getString("id") }.distinct().size == lines.size)
        val requirements = lines.map { line ->
            val perSet = line.getInt("quantity_per_set"); require(perSet > 0)
            BomRequirement(BomRequirementIdentity(line.getString("id")), line.nullableString("sku"), line.nullableString("name"),
                line.nullableString("model"), line.nullableString("package_name"), perSet, Math.multiplyExact(perSet, sets),
                line.getJSONArray("source_rows").objects().map { BomSourceRow(it.getString("sheet_name"), it.getInt("row_number"), it.getInt("quantity_per_set"), it.getJSONObject("fields").stringMap()) })
        }
        val orphaned = record.optJSONObject("_android_legacy_choices")
        return BomPreset(record.getString("id"), record.getString("name"), BomParseResult(
            content.getString("project_name"), sets, content.getJSONArray("available_sheets").objects().map(::decodeSheet),
            decodeSheet(content.getJSONObject("selected_sheet")), requirements, content.getString("file_sha256"),
            content.optString("source_name"), content.optJSONArray("warnings")?.strings().orEmpty()),
            orphaned?.optJSONObject("selections")?.stringMap().orEmpty() + lines.filterNot { it.isNull("selection") }.associate { it.getString("id") to it.getString("selection") },
            orphaned?.optJSONArray("excluded_keys")?.strings().orEmpty().toSet() + lines.filter { it.getBoolean("excluded") }.map { it.getString("id") }.toSet())
    }
    fun decodeLegacy(root: JSONObject): List<BomPreset> = root.getJSONArray("presets").objects().map { json ->
        val parsed = json.getJSONObject("parsed")
        BomPreset(json.getString("id"), json.getString("name"), BomParseResult(
            parsed.getString("projectName"), parsed.getInt("productionSets"), parsed.getJSONArray("availableSheets").objects().map(::decodeSheet),
            decodeSheet(parsed.getJSONObject("selectedSheet")), parsed.getJSONArray("requirements").objects().map { line -> BomRequirement(
                BomRequirementIdentity(line.getString("identity")), line.nullableString("sku"), line.nullableString("name"), line.nullableString("model"), line.nullableString("packageName"),
                line.getInt("quantityPerSet"), line.getInt("requiredQuantity"), line.getJSONArray("sourceRows").objects().map {
                    BomSourceRow(it.getString("sheetName"), it.getInt("rowNumber"), it.getInt("quantityPerSet"), it.getJSONObject("fields").stringMap()) }) },
            parsed.getString("fileSha256")), json.getJSONObject("selections").stringMap(), json.getJSONArray("excludedKeys").strings().toSet())
    }
    private fun sheet(sheet: BomSheet) = JSONObject().put("name", sheet.name).put("index", sheet.index).put("hidden", sheet.hidden)
    private fun decodeSheet(json: JSONObject) = BomSheet(json.getString("name"), json.getInt("index"), json.getBoolean("hidden"))
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
}
private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
