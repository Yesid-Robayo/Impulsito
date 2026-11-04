package io.github.yesidrobayo.shopledger.data.local

import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT

// Filas válidas de ubicaciones, transferencias, producción y conteos, escritas como SQL para las
// sondas de sus guardas. Todos los nombres, cantidades y montos son inventados.

/** Una ubicación que creó la persona: con nombre y sin clave inicial. */
internal fun locationRow(name: String): Row = row(
    "system_key" to null,
    "name" to name,
    "normalized_name" to name,
    "kind" to "WAREHOUSE",
    "archived_at" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

/** Una transferencia activa entre dos ubicaciones. */
internal fun transferRow(fromLocationId: Long, toLocationId: Long): Row = row(
    "from_location_id" to fromLocationId,
    "to_location_id" to toLocationId,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "created_at" to CREATED_AT,
    "status" to "ACTIVE",
    "voided_at" to null,
    "void_reason" to null,
    "void_note" to null,
)

/** Una línea de transferencia de cuatro unidades, sin lote. */
internal fun transferLineRow(transferId: Long, productId: Long): Row = row(
    "transfer_id" to transferId,
    "product_id" to productId,
    "lot_id" to null,
    "quantity_milli" to 4_000L,
)

/** Un lote de producción activo de diez unidades, que costó 17.500, en la ubicación inicial. */
internal fun batchRow(productId: Long): Row = row(
    "product_id" to productId,
    "location_id" to LOCATION_ID,
    "quantity_milli" to 10_000L,
    "total_cost" to 17_500L,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "created_at" to CREATED_AT,
    "status" to "ACTIVE",
    "voided_at" to null,
    "void_reason" to null,
    "void_note" to null,
)

/** Un insumo de producción: dos unidades a 8.000, sin lote. */
internal fun batchLineRow(batchId: Long, componentId: Long): Row = row(
    "production_batch_id" to batchId,
    "component_product_id" to componentId,
    "lot_id" to null,
    "quantity_milli" to 2_000L,
    "unit_cost" to 8_000L,
    "line_cost" to 16_000L,
)

/** Una sesión de conteo abierta, a ciegas, de la ubicación inicial y sin categoría. */
internal fun countSessionRow(): Row = row(
    "status" to "OPEN",
    "is_blind" to 1L,
    "location_id" to LOCATION_ID,
    "category_id" to null,
    "note" to null,
    "started_at" to CREATED_AT,
    "finished_at" to null,
    "business_date" to BUSINESS_DATE,
)

/** Una línea de conteo con diez unidades esperadas y todavía sin contar. */
internal fun countLineRow(sessionId: Long, productId: Long): Row = row(
    "count_session_id" to sessionId,
    "product_id" to productId,
    "lot_id" to null,
    "expected_milli" to 10_000L,
    "counted_milli" to null,
)
