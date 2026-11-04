package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/**
 * Lecturas y escrituras de `media_assets`, los metadatos de las imágenes. Las escrituras solo se
 * llaman dentro de la transacción del caso de uso.
 *
 * La fila nunca cambia de imagen: solo se marca o se desmarca como huérfana. Sin `REPLACE`: un
 * `INSERT OR REPLACE` borraría la fila que choca y dejaría sin foto, por su llave, a los productos
 * que la usan. La misma imagen dos veces falla por el índice único de `sha256`.
 */
@Dao
internal interface MediaAssetDao {
    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun findById(id: Long): MediaAssetEntity?

    /** La fila de la imagen con ese contenido, si ya se guardó. */
    @Query("SELECT * FROM media_assets WHERE sha256 = :sha256")
    suspend fun findBySha256(sha256: String): MediaAssetEntity?

    /** Las huérfanas desde antes de [orphanedBefore], por `id`: las que la limpieza ya puede borrar. */
    @Query(
        "SELECT * FROM media_assets WHERE orphaned_at IS NOT NULL " +
            "AND orphaned_at < :orphanedBefore ORDER BY id",
    )
    suspend fun findOrphanedBefore(orphanedBefore: Long): List<MediaAssetEntity>

    /** Devuelve el id nuevo. Falla si la imagen ya existe. */
    @Insert
    suspend fun insert(asset: MediaAssetEntity): Long

    /** Marca desde cuándo no la usa nadie, o con `null` que vuelve a usarse. */
    @Query("UPDATE media_assets SET orphaned_at = :orphanedAt WHERE id = :id")
    suspend fun setOrphanedAt(id: Long, orphanedAt: Long?): Int

    /** Borra la fila. Quien todavía la nombre queda sin imagen por su llave, no con un id roto. */
    @Query("DELETE FROM media_assets WHERE id = :id")
    suspend fun delete(id: Long): Int
}
