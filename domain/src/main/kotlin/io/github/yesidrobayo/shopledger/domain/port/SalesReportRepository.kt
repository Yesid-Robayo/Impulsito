package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesTotals
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas de ventas y devoluciones de un período. Solo lee.
 *
 * Suma lo guardado en la cabecera de cada documento, que ya es la suma de sus líneas, con el costo
 * congelado al vender: nunca vuelve a calcular un costo desde el producto.
 */
interface SalesReportRepository {
    /**
     * Las sumas de [period], de nuevo cada vez que se confirma un cambio en ventas o devoluciones,
     * sin repetir un resultado igual al anterior.
     */
    fun observeTotals(period: ReportPeriod): Flow<SalesTotals>
}
