package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Los metadatos de una foto o una imagen. La imagen nunca entra en la base: vive como archivo,
 * nombrado por su `sha256`, y viaja aparte de la copia de la base.
 *
 * - **Sin rutas ni referencias del destino.** El archivo se encuentra por su `sha256`, así que la
 *   fila sirve igual en otro teléfono o tras restaurar. Una ruta o un id remoto guardados aquí
 *   quedarían viejos al cambiar de teléfono o de destino, y dirían en la copia dónde vive cada
 *   imagen.
 * - El `sha256` es único: la misma imagen se guarda una sola vez, aunque la usen varios productos.
 * - Que falte el archivo no es un fallo: la fila queda, y la pantalla muestra que no hay imagen.
 * - `orphaned_at` marca cuándo dejó de tener referencias. A los 30 días la limpieza la borra, y su
 *   índice sirve a esa búsqueda. Borrarla deja sin foto, por su llave, al producto que la nombre.
 */
@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["sha256"], unique = true),
        Index(value = ["orphaned_at"]),
    ],
)
internal data class MediaAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "width_px") val widthPx: Long,
    @ColumnInfo(name = "height_px") val heightPx: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "orphaned_at") val orphanedAt: Long?,
)
