package com.componentvault.android.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private val lastMutationMillis = AtomicLong(0L)
private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withLocale(Locale.US).withZone(ZoneOffset.UTC)

internal fun utcNow(): String {
    val millis = lastMutationMillis.updateAndGet { previous ->
        maxOf(System.currentTimeMillis(), previous + 1L)
    }
    return timestampFormatter.format(Instant.ofEpochMilli(millis))
}

internal fun randomId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

internal fun ensureStorageLocation(db: SQLiteDatabase, id: String, updatedAt: String) {
    require(id.isNotBlank() && id.length <= 120) { "Invalid storage location code." }
    val deleted=db.rawQuery("SELECT deleted FROM storage_locations WHERE id = ?",arrayOf(id)).use{it.moveToFirst()&&it.getInt(0)==1}
    check(!deleted) { "库位已删除，请改用其他编号的库位。" }
    db.insertWithOnConflict(
        "storage_locations",
        null,
        ContentValues().apply {
            put("id", id)
            put("name", id)
            put("updated_at", updatedAt)
            put("deleted", 0)
        },
        SQLiteDatabase.CONFLICT_IGNORE,
    )
}

internal fun allocationQuantity(db: SQLiteDatabase, componentId: String, locationId: String): Int =
    db.rawQuery(
        "SELECT quantity FROM component_allocations WHERE component_id = ? AND location_id = ?",
        arrayOf(componentId, locationId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

internal fun setAllocationQuantity(db: SQLiteDatabase, componentId: String, locationId: String, quantity: Int) {
    db.insertWithOnConflict(
        "component_allocations",
        null,
        ContentValues().apply {
            put("component_id", componentId)
            put("location_id", locationId)
            put("quantity", quantity)
        },
        SQLiteDatabase.CONFLICT_REPLACE,
    )
}

internal fun verifyAllocationTotal(db: SQLiteDatabase, componentId: String, expected: Int) {
    val total = db.rawQuery(
        "SELECT COALESCE(SUM(quantity), 0) FROM component_allocations WHERE component_id = ?",
        arrayOf(componentId),
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
    check(total == expected.toLong()) { "Allocation total does not match component quantity." }
}

internal fun consumeAllocations(
    db: SQLiteDatabase,
    componentId: String,
    requested: Int,
): List<Pair<String, Int>> {
    var remaining = requested
    val consumed = mutableListOf<Pair<String, Int>>()
    db.rawQuery(
        "SELECT location_id, quantity FROM component_allocations WHERE component_id = ? AND quantity > 0 " +
            "ORDER BY quantity DESC, location_id ASC",
        arrayOf(componentId),
    ).use { cursor ->
        while (cursor.moveToNext() && remaining > 0) {
            val locationId = cursor.getString(0)
            val available = cursor.getInt(1)
            val take = minOf(available, remaining)
            setAllocationQuantity(db, componentId, locationId, available - take)
            consumed += locationId to take
            remaining -= take
        }
    }
    check(remaining == 0) { "Allocated stock is insufficient; refresh the BOM preview." }
    return consumed
}

internal fun allocationPlan(db: SQLiteDatabase, componentId: String, requested: Int): List<Pair<String, Int>> {
    val available = db.rawQuery(
        "SELECT location_id, quantity FROM component_allocations WHERE component_id = ? AND quantity > 0",
        arrayOf(componentId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1)) } }
    return com.componentvault.android.data.bom.BomAllocationPlanner.plan(requested, available)
        .map { it.locationId to it.quantity }
}

internal fun enqueueEntity(
    db: SQLiteDatabase,
    entityType: String,
    entityId: String,
    entityUpdatedAt: String,
) {
    val values = ContentValues().apply {
        put("entity_type", entityType)
        put("entity_id", entityId)
        put("entity_updated_at", entityUpdatedAt)
        put("created_at", utcNow())
    }
    db.insertWithOnConflict(
        "sync_queue",
        null,
        values,
        SQLiteDatabase.CONFLICT_REPLACE,
    )
}

