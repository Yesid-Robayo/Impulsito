package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlinx.coroutines.flow.Flow

/**
 * Cuántas veces se vendió cada producto: una consulta de solo lectura sobre las líneas de venta,
 * para ordenar `Más vendidos` sin guardar ningún contador aparte.
 */
fun interface SaleFrequencySource {
    /**
     * Cuántas líneas de ventas activas tiene cada producto, por id, desde la fecha de negocio
     * [since], incluida. Un producto sin líneas no aparece. Vuelve a emitir con cada venta que se
     * confirma o se anula.
     */
    fun observeLineCounts(since: BusinessDate): Flow<Map<Long, Int>>
}
