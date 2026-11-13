package io.github.yesidrobayo.shopledger.domain.model.customer

/**
 * Los puntos vigentes de todos los clientes: [totalPoints] suma los saldos positivos, y
 * [customersWithPoints] cuenta quién tiene alguno. Un saldo negativo, que deja anular una venta
 * cuyos puntos ya se gastaron, no resta de los demás.
 */
data class PointsSummary(val totalPoints: Long, val customersWithPoints: Int) {
    companion object {
        val NONE: PointsSummary = PointsSummary(0L, 0)
    }
}
