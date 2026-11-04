package io.github.yesidrobayo.shopledger.data.local.audit

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Un evento del registro de auditoría: qué pasó y cuándo, nada más.
 *
 * - Sin montos, nombres, direcciones, usuarios ni rutas: el registro se ve en el teléfono y viaja
 *   en la copia, así que solo guarda lo que no expone el negocio ni a la persona. Por eso no tiene
 *   columnas para detalles: un detalle libre terminaría guardando lo que no debe.
 * - `event_type` y `result` son los nombres guardados de `AuditEventType` y `AuditResult`.
 * - `occurred_at` sale del reloj del teléfono: informa, no ordena. El orden es el `id`.
 *
 * Solo `Long` y `String`, sin valores por defecto en las columnas ni índices: se lee siempre desde
 * el más nuevo por la llave primaria.
 */
@Entity(tableName = "audit_events")
internal data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "result") val result: String,
)
