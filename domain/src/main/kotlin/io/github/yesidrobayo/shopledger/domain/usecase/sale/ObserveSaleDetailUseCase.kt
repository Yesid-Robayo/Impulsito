package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleReturnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Una venta con sus líneas, sus pagos y sus devoluciones, de nuevo con cada cambio confirmado: al
 * anularla o devolver algo, el detalle lo muestra sin reabrirlo. `null` si la venta no existe. No
 * escribe nada.
 *
 * Sin [returns], la venta se muestra sin devoluciones: solo lo omiten las pruebas que no las miran.
 */
class ObserveSaleDetailUseCase(
    private val sales: SaleRepository,
    private val payments: SalePaymentRepository,
    private val products: ProductRepository,
    private val returns: SaleReturnRepository? = null,
) {
    operator fun invoke(saleId: Long): Flow<SaleDetail?> = combine(
        sales.observeById(saleId),
        sales.observeLines(saleId),
        payments.observeBySale(saleId),
        products.observe(ArchiveFilter.ARCHIVED),
        returnsOf(saleId),
    ) { sale, lines, salePayments, archived, saleReturns ->
        sale?.let {
            val used = lines.map { line -> line.content.productId }.toSet()
            val archivedIds = archived.map { product -> product.id }.filter { id -> id in used }
            SaleDetail(
                it,
                lines,
                salePayments,
                archivedIds.toSet(),
                saleReturns.first,
                saleReturns.second,
            )
        }
    }

    /** Las devoluciones de la venta y lo que ya devolvió cada línea, juntas. */
    private fun returnsOf(saleId: Long): Flow<Pair<List<SaleReturn>, Map<Long, LineAmounts>>> {
        val book = returns ?: return flowOf(emptyList<SaleReturn>() to emptyMap())
        return combine(book.observeBySale(saleId), book.observeTakenBySale(saleId)) { list, taken ->
            list to taken
        }
    }
}
