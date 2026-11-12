package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals

/**
 * Las sumas de un período que necesitan las estadísticas: las de su ganancia y cuántas ventas hubo.
 *
 * Cada suma cuenta solo documentos activos cuya fecha de negocio cae en el período, cada uno con su
 * propia fecha. Las cifras que se muestran salen de aquí con las reglas de `:domain`: la base solo
 * suma, nunca resta ni divide.
 */
data class PeriodTotals(
    /** Las sumas con que se calcula la ganancia del período. */
    val profit: ProfitTotals,
    /** Cuántas ventas activas hubo. Una devolución no lo cambia: la venta sí ocurrió. */
    val salesCount: Long,
) {
    companion object {
        /** Un período sin documentos. */
        val ZERO =
            PeriodTotals(
                profit =
                    ProfitTotals(
                        salesNetTotal = Money.ZERO,
                        returnsNetRefund = Money.ZERO,
                        salesTotalCost = Money.ZERO,
                        returnsCostReturned = Money.ZERO,
                        recordedExpenses = Money.ZERO,
                        netShrinkage = Money.ZERO,
                        paymentFees = Money.ZERO,
                        cashRoundingTotal = Money.ZERO,
                    ),
                salesCount = 0L,
            )
    }
}
