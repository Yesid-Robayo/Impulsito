package io.github.yesidrobayo.shopledger.data.local.backup

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * El estado del respaldo, en su única fila, de `id` 1, que la base crea al nacer.
 *
 * La marca de cambios son dos contadores, no un booleano:
 * - `change_seq` cuenta los cambios de negocio confirmados, y nunca baja: sube en la misma
 *   transacción que cada caso de uso que escribe, así que una venta no queda sin marcar.
 * - `verified_seq` es el `change_seq` que trae la última copia verificada en el destino. Nunca
 *   supera a `change_seq`, y la diferencia son los cambios que todavía no tienen copia.
 * - `first_unsynced_at` es el primer cambio sin copia verificada, de donde se mide cuánto lleva el
 *   negocio sin respaldo.
 *
 * Lo demás describe el destino, la última ejecución y la cabeza conocida de las copias:
 * - Sin red de terceros ni cuentas: `destination_kind` es el nombre guardado del destino que eligió
 *   la persona, `destination_account` el usuario del servicio, `destination_id` una huella del
 *   destino para compararlo, y `destination_folder` la carpeta. Nunca hay contraseñas: esas viven
 *   selladas fuera de la base. Los dos últimos y el tipo se conservan al desconectar.
 * - `failure_code`, `last_failure_kind`, `link_state`, `conflict_kind` y `last_drill_result` son
 *   nombres guardados de sus enums. `consecutive_failures` cuenta los fallos de transporte
 *   seguidos, e `invariant_failures` los productos que no pasaron la última verificación.
 * - Los instantes `last_*` salen del reloj del teléfono: solo informan, nunca deciden qué copia
 *   manda. `last_upload_finished_at` es la base del intervalo con datos móviles.
 * - `head_*` es la cabeza conocida del destino, `pending_*` la subida en curso y `conflict_*` el
 *   conflicto pendiente. `*_file_ref` es el nombre del archivo, o la letra de la ranura.
 * - `copy_*` es el sello: solo tiene valor dentro de un archivo de copia, nunca en la base activa.
 *
 * Solo `Long` y `String`, con los nombres de las columnas del modelo de datos. El `id` no se
 * genera: siempre es 1. Sin valores por defecto en las columnas: los iniciales los escribe el
 * código al crear la base.
 */
@Entity(tableName = "backup_state")
internal data class BackupStateEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "change_seq") val changeSeq: Long,
    @ColumnInfo(name = "verified_seq") val verifiedSeq: Long,
    @ColumnInfo(name = "first_unsynced_at") val firstUnsyncedAt: Long?,
    @ColumnInfo(name = "link_state") val linkState: String,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Long,
    @ColumnInfo(name = "last_failure_kind") val lastFailureKind: String?,
    @ColumnInfo(name = "invariant_failures") val invariantFailures: Long,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long?,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long?,
    @ColumnInfo(name = "last_upload_finished_at") val lastUploadFinishedAt: Long?,
    @ColumnInfo(name = "last_stale_notice_at") val lastStaleNoticeAt: Long?,
    @ColumnInfo(name = "last_drill_at") val lastDrillAt: Long?,
    @ColumnInfo(name = "last_drill_result") val lastDrillResult: String?,
    @ColumnInfo(name = "last_full_verify_at") val lastFullVerifyAt: Long?,
    @ColumnInfo(name = "last_human_check_at") val lastHumanCheckAt: Long?,
    @ColumnInfo(name = "human_check_generation") val humanCheckGeneration: Long?,
    @ColumnInfo(name = "destination_kind") val destinationKind: String?,
    @ColumnInfo(name = "destination_account") val destinationAccount: String?,
    @ColumnInfo(name = "destination_id") val destinationId: String?,
    @ColumnInfo(name = "destination_folder") val destinationFolder: String?,
    @ColumnInfo(name = "head_generation") val headGeneration: Long,
    @ColumnInfo(name = "head_device_id") val headDeviceId: String?,
    @ColumnInfo(name = "head_file_ref") val headFileRef: String?,
    @ColumnInfo(name = "shrink_accepted_generation") val shrinkAcceptedGeneration: Long?,
    @ColumnInfo(name = "pending_generation") val pendingGeneration: Long?,
    @ColumnInfo(name = "pending_seq") val pendingSeq: Long?,
    @ColumnInfo(name = "pending_sha256") val pendingSha256: String?,
    @ColumnInfo(name = "pending_size_bytes") val pendingSizeBytes: Long?,
    @ColumnInfo(name = "conflict_kind") val conflictKind: String?,
    @ColumnInfo(name = "conflict_generation") val conflictGeneration: Long?,
    @ColumnInfo(name = "conflict_device_id") val conflictDeviceId: String?,
    @ColumnInfo(name = "conflict_file_ref") val conflictFileRef: String?,
    @ColumnInfo(name = "copy_generation") val copyGeneration: Long?,
    @ColumnInfo(name = "copy_device_id") val copyDeviceId: String?,
    @ColumnInfo(name = "copy_created_at") val copyCreatedAt: Long?,
)
