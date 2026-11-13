package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.ColumnInfo

/** Los puntos vigentes de todos los clientes, y cuántos clientes tienen alguno. */
internal data class PointsSummaryRow(
    @ColumnInfo(name = "total_points") val totalPoints: Long,
    @ColumnInfo(name = "customers") val customers: Long,
)
