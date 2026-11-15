package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lo que el resumen de gestión necesita saber de la base además de las sumas de las
 * estadísticas. Solo lee, y cada pregunta se detiene en la primera fila que la responde.
 */
@Dao
internal interface ManagementSummaryDao {
    /**
     * Si hay compras activas entre [startDate] y [endDate], las dos incluidas, por su índice
     * `(status, business_date)`; si algún producto tiene movimientos de stock hasta [endDate]; y
     * si hay algún cliente o proveedor sin archivar. Vuelve a emitir con cada cambio en esas
     * tablas.
     */
    @Query(
        """
        SELECT
          EXISTS (
            SELECT 1 FROM purchases
            WHERE status = 'ACTIVE' AND business_date BETWEEN :startDate AND :endDate
          ) AS has_purchases,
          EXISTS (SELECT 1 FROM stock_movements WHERE business_date <= :endDate)
            AS has_inventory,
          (
            EXISTS (SELECT 1 FROM customers WHERE archived_at IS NULL)
            OR EXISTS (SELECT 1 FROM suppliers WHERE archived_at IS NULL)
          ) AS has_parties
        """,
    )
    fun observeAvailability(startDate: String, endDate: String): Flow<SummaryAvailabilityRow>
}

/** Las respuestas de [ManagementSummaryDao.observeAvailability], 1 o 0. */
internal data class SummaryAvailabilityRow(
    @ColumnInfo(name = "has_purchases") val hasPurchases: Long,
    @ColumnInfo(name = "has_inventory") val hasInventory: Long,
    @ColumnInfo(name = "has_parties") val hasParties: Long,
)
