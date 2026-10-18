package com.componentvault.android.data

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ComponentHubImporter(private val databaseHelper: InventoryDatabaseHelper) {
    suspend fun importComponentHub(
        preview: com.componentvault.android.data.bom.ComponentHubParseResult,
    ): com.componentvault.android.data.bom.ComponentHubImportResult = withContext(Dispatchers.IO) {
        if (!preview.canConfirm) {
            return@withContext com.componentvault.android.data.bom.ComponentHubImportResult(
                com.componentvault.android.data.bom.ComponentHubImportOutcome.REJECTED,
                "迁移预览仍有冲突或无效记录，请先处理。",
            )
        }
        databaseHelper.writableDatabase.use { db ->
            db.beginTransaction()
            try {
                val alreadyImported = db.rawQuery(
                    "SELECT 1 FROM component_hub_imports WHERE source_fingerprint = ?",
                    arrayOf(preview.fileSha256),
                ).use { it.moveToFirst() }
                if (alreadyImported) {
                    return@withContext com.componentvault.android.data.bom.ComponentHubImportResult(
                        com.componentvault.android.data.bom.ComponentHubImportOutcome.ALREADY_APPLIED,
                        "此 Component Hub 文件已经导入，未重复创建库存。",
                    )
                }
                val existing = db.rawQuery(
                    "SELECT sku FROM components WHERE deleted = 0",
                    null,
                ).use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0).trim().uppercase()) }
                }
                check(preview.components.none { it.sku.trim().uppercase() in existing }) {
                    "预览后库存已变化，请重新核对重复 SKU。"
                }

                val now = utcNow()
                preview.components.forEach { source ->
                    val componentId = "cmp-${randomId()}"
                    db.insertOrThrow(
                        "components",
                        null,
                        ContentValues().apply {
                            put("id", componentId)
                            put("sku", source.sku.trim())
                            put("name", source.name.trim())
                            put("category", source.category.trim())
                            put("package_name", source.packageName.trim())
                            put("location", source.location.trim())
                            put("description", source.notes.joinToString("\n"))
                            put("quantity", source.quantity)
                            put("min_stock", source.minStock)
                            put("updated_at", now)
                            put("deleted", 0)
                        },
                    )
                    ensureStorageLocation(db, source.location.trim(), now)
                    setAllocationQuantity(db, componentId, source.location.trim(), source.quantity)
                    verifyAllocationTotal(db, componentId, source.quantity)
                    enqueueEntity(db, "component", componentId, now)
                    if (source.quantity > 0) {
                        val movementId = "mov-${randomId()}"
                        db.insertOrThrow(
                            "stock_movements",
                            null,
                            ContentValues().apply {
                                put("id", movementId)
                                put("component_id", componentId)
                                put("movement_type", "inbound")
                                put("quantity", source.quantity)
                                put("reason", "Component Hub import")
                                put("note", "Source ${preview.fileSha256}")
                                put("happened_at", now)
                                put("updated_at", now)
                                put("deleted", 0)
                                put("location_id", source.location.trim())
                            },
                        )
                        enqueueEntity(db, "stock_movement", movementId, now)
                    }
                }
                db.insertOrThrow(
                    "component_hub_imports",
                    null,
                    ContentValues().apply {
                        put("source_fingerprint", preview.fileSha256)
                        put("imported_count", preview.components.size)
                        put("skipped_count", preview.skippedDuplicateCount)
                        put("created_at", now)
                    },
                )
                db.setTransactionSuccessful()
                com.componentvault.android.data.bom.ComponentHubImportResult(
                    com.componentvault.android.data.bom.ComponentHubImportOutcome.APPLIED,
                    "Component Hub 迁移已完成。",
                    preview.components.size,
                    preview.skippedDuplicateCount,
                )
            } catch (error: Exception) {
                com.componentvault.android.data.bom.ComponentHubImportResult(
                    com.componentvault.android.data.bom.ComponentHubImportOutcome.REJECTED,
                    error.message ?: "Component Hub 迁移失败，整批已回滚。",
                )
            } finally {
                db.endTransaction()
            }
        }
    }

}
