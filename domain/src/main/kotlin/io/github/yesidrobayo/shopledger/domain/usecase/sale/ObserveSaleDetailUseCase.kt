package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Una venta con sus líneas y sus pagos, de nuevo con cada cambio confirmado: al anularla, el
 * detalle la muestra anulada sin reabrirlo. `null` si la venta no existe. No escribe nada.
 */
class ObserveSaleDetailUseCase(
    private val sales: SaleRepository,
    private val payments: SalePaymentRepository,
    private val products: ProductRepository,
) {
    operator fun invoke(saleId: Long): Flow<SaleDetail?> = combine(
        sales.observeById(saleId),
        sales.observeLines(saleId),
        payments.observeBySale(saleId),
        products.observe(ArchiveFilter.ARCHIVED),
    ) { sale, lines, salePayments, archived ->
        sale?.let {
            val used = lines.map { line -> line.content.productId }.toSet()
            val archivedIds = archived.map { product -> product.id }.filter { id -> id in used }
            SaleDetail(it, lines, salePayments, archivedIds.toSet())
        }
    }
}
