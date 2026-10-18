package com.componentvault.android.data.bom

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class BomProcurementStatus { InTransit, ArrivedNeedsReview }

data class BomProcurementEntry(
    val projectKey: String,
    val projectName: String,
    val requirementCanonicalKey: String,
    val sku: String?,
    val model: String?,
    val packageName: String?,
    val displayName: String,
    val status: BomProcurementStatus,
)

class BomProcurementStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

data class BomInboundItem(val sku: String?, val model: String?, val packageName: String?)

/** Store at File(context.filesDir, "bom-procurement.json"); independent of inventory sync. */
class BomProcurementStore(private val file: File) {
    companion object {
        private val lock = Any()
        fun projectKey(parsed: BomParseResult): String = "${parsed.fileSha256}:${parsed.projectName.trim()}"
    }

    fun list(projectKey: String? = null): List<BomProcurementEntry> = synchronized(lock) {
        read().filter { projectKey == null || it.projectKey == projectKey }
    }

    fun mark(
        projectKey: String,
        projectName: String,
        requirement: BomRequirement,
        displayName: String = requirement.name ?: requirement.model ?: requirement.sku.orEmpty(),
    ): BomProcurementEntry = synchronized(lock) {
        markAllLocked(projectKey, projectName, listOf(requirement), mapOf(requirement.identity.canonicalKey to displayName)).single()
    }

    fun markAll(projectKey: String, projectName: String, requirements: List<BomRequirement>): List<BomProcurementEntry> =
        synchronized(lock) { markAllLocked(projectKey, projectName, requirements, emptyMap()) }

    private fun markAllLocked(projectKey: String, projectName: String, requirements: List<BomRequirement>,
                              names: Map<String, String>): List<BomProcurementEntry> {
        require(projectKey.isNotBlank() && projectName.isNotBlank()) { "BOM 项目不能为空。" }
        if (requirements.isEmpty()) return emptyList()
        val entries = read()
        val byKey = entries.associateBy { it.projectKey to it.requirementCanonicalKey }.toMutableMap()
        val marked = requirements.distinctBy { it.identity.canonicalKey }.map { requirement ->
            val key = requirement.identity.canonicalKey
            require(key.isNotBlank()) { "BOM 需求标识不能为空。" }
            val previous = byKey[projectKey to key]
            val entry = if (previous != null && previous.status == BomProcurementStatus.InTransit) previous else
                BomProcurementEntry(projectKey, projectName.trim(), key,
                    requirement.sku?.trim()?.takeIf(String::isNotBlank),
                    requirement.model?.trim()?.takeIf(String::isNotBlank),
                    requirement.packageName?.trim()?.takeIf(String::isNotBlank),
                    (names[key] ?: requirement.name ?: requirement.model ?: requirement.sku ?: key).trim(),
                    BomProcurementStatus.InTransit)
            byKey[projectKey to key] = entry
            entry
        }
        if (byKey.values.toList() != entries) write(byKey.values.toList())
        return marked
    }

    fun remove(projectKey: String, requirementCanonicalKey: String): Boolean = synchronized(lock) {
        val entries = read()
        val remaining = entries.filterNot {
            it.projectKey == projectKey && it.requirementCanonicalKey == requirementCanonicalKey
        }
        if (remaining.size == entries.size) false else { write(remaining); true }
    }

    fun matchInbound(sku: String?, model: String?, packageName: String?): List<BomProcurementEntry> =
        matchInboundAll(listOf(BomInboundItem(sku, model, packageName)))

    fun matchInboundAll(items: List<BomInboundItem>): List<BomProcurementEntry> = synchronized(lock) {
        if (items.isEmpty()) return@synchronized emptyList()
        val normalizedItems = items.map { Triple(normalized(it.sku), normalized(it.model), normalized(it.packageName)) }
        val entries = read()
        val arrived = entries.filter { entry ->
            entry.status == BomProcurementStatus.InTransit &&
                normalizedItems.any { (sku, model, packageName) ->
                    if (!entry.sku.isNullOrBlank()) sku != null && normalized(entry.sku) == sku
                    else model != null && packageName != null &&
                        normalized(entry.model) == model && normalized(entry.packageName) == packageName
                }
        }
        if (arrived.isNotEmpty()) {
            val keys = arrived.map { it.projectKey to it.requirementCanonicalKey }.toSet()
            write(entries.map { entry -> if (entry.projectKey to entry.requirementCanonicalKey in keys)
                entry.copy(status = BomProcurementStatus.ArrivedNeedsReview) else entry })
        }
        arrived.map { it.copy(status = BomProcurementStatus.ArrivedNeedsReview) }
    }

    private fun normalized(value: String?): String? = value?.trim()?.takeIf(String::isNotBlank)
        ?.uppercase(Locale.ROOT)

    private fun read(): List<BomProcurementEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.getInt("version") == 1)
            val array = root.getJSONArray("entries")
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                BomProcurementEntry(item.getString("projectKey"), item.getString("projectName"),
                    item.getString("requirementCanonicalKey"), item.optionalText("sku"),
                    item.optionalText("model"), item.optionalText("packageName"),
                    item.getString("displayName"), BomProcurementStatus.valueOf(item.getString("status")))
            }
        } catch (error: Exception) {
            throw BomProcurementStoreException("无法读取 BOM 在途提醒；请检查或备份 ${file.name} 后重试。", error)
        }
    }

    private fun JSONObject.optionalText(key: String): String? =
        if (isNull(key)) null else getString(key).takeIf(String::isNotBlank)

    private fun write(entries: List<BomProcurementEntry>) {
        val parent = file.absoluteFile.parentFile
        if (!parent.exists() && !parent.mkdirs())
            throw BomProcurementStoreException("无法创建 BOM 在途提醒目录：${parent.path}")
        val temp = File(parent, "${file.name}.tmp")
        try {
            val array = JSONArray().apply { entries.forEach { entry ->
                put(JSONObject().put("projectKey", entry.projectKey).put("projectName", entry.projectName)
                    .put("requirementCanonicalKey", entry.requirementCanonicalKey)
                    .put("sku", entry.sku ?: JSONObject.NULL).put("model", entry.model ?: JSONObject.NULL)
                    .put("packageName", entry.packageName ?: JSONObject.NULL)
                    .put("displayName", entry.displayName).put("status", entry.status.name))
            } }
            temp.writeText(JSONObject().put("version", 1).put("entries", array).toString(), Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            throw BomProcurementStoreException("无法保存 BOM 在途提醒；请检查设备存储空间后重试。", error)
        } finally { temp.delete() }
    }
}
