package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxTotals
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas de impuesto de un período, para el impuesto estimado. Solo lee.
 *
 * Suma lo guardado en la cabecera de cada documento, que es la suma de sus líneas: nunca vuelve a
 * calcular un impuesto desde el precio.
 */
interface TaxReportRepository {
    /**
     * Las sumas de [period], de nuevo cada vez que se confirma un cambio en ventas, devoluciones o
     * compras.
     */
    fun observeTotals(period: ReportPeriod): Flow<TaxTotals>
}
