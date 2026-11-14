package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlinx.coroutines.flow.Flow

/**
 * Cuánto salió de cada producto por ventas: una consulta de solo lectura sobre el libro de stock,
 * para sugerir cuánto reponer sin guardar ningún contador aparte.
 */
fun interface SaleOutflowSource {
    /**
     * Las milésimas que salieron de cada producto en filas `SALE` que nada compensó, por id, desde
     * la fecha de negocio [since], incluida. Con [locationId], solo las que salieron de esa
     * ubicación. Una venta anulada deja de contar; una devolución no la compensa, porque es otra
     * entrada. Un producto sin salidas no aparece. Vuelve a emitir con cada cambio del libro.
     */
    fun observeUnitsOut(since: BusinessDate, locationId: Long?): Flow<Map<Long, QuantityMilli>>
}
