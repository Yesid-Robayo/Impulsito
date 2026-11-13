package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.NewHeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.HeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.pricing.LinePriceList
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
import io.github.yesidrobayo.shopledger.domain.rule.sale.HeldSaleRules
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleLineRules
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits

/**
 * Lo que se pone en espera: las líneas de la venta en edición, el nombre que escribió la persona,
 * si la venta ya venía de la espera, cuál es, para guardarla encima y no repetirla, y la lista de
 * precios elegida, `null` para la inicial.
 */
data class HoldSaleRequest(
    val lines: List<SaleLineRequest>,
    val label: String?,
    val heldSaleId: Long? = null,
    val priceListId: Long? = null,
)

/**
 * Pone una venta en espera para atender a otra persona, en una sola transacción.
 *
 * - **No es una venta.** No toca stock, libro, dinero ni reportes, y no reserva nada. Se confirma
 *   sin contar como cambio sin respaldar, porque no es un hecho del negocio, aunque viaja en la
 *   siguiente copia.
 * - **Se guarda lo escrito.** Un precio escrito a mano en la línea se conserva; sin él, la línea
 *   toma el precio vigente al retomarla. Un descuento en porcentaje se guarda convertido en monto,
 *   con el precio que tenía la línea: la base no guarda porcentajes.
 * - **Una venta retomada se guarda encima de la suya,** con su fecha de espera original. Si alguien
 *   la descartó en el intermedio, se guarda como una nueva: lo escrito nunca se pierde.
 * - **La lista de precios viaja con la espera.** Un descuento en porcentaje se convierte con el
 *   precio de esa lista, el mismo que se veía en el carrito.
 */
class HoldSaleUseCase(
    private val transactions: TransactionRunner,
    private val heldSales: HeldSaleRepository,
    private val products: ProductRepository,
    private val clock: Clock,
    private val productUnits: ProductUnitRepository? = null,
    private val priceLists: PriceListRepository? = null,
    private val productPrices: ProductPriceRepository? = null,
) {
    suspend operator fun invoke(request: HoldSaleRequest): HoldSaleResult = try {
        transactions.write { hold(request) }
    } catch (failure: StorageFailureException) {
        HoldSaleResult.StorageFailed(failure.failure)
    }

    private suspend fun hold(request: HoldSaleRequest): TxResult<HoldSaleResult> {
        if (request.lines.isEmpty()) return TxResult.Rollback(HoldSaleResult.EmptySale)
        if (request.lines.size > Limit.DOCUMENT_LINES.max) {
            return TxResult.Rollback(HoldSaleResult.TooManyLines)
        }
        val label = HeldSaleRules.labelOf(request.label)
        if (!HeldSaleRules.fits(label)) return TxResult.Rollback(HoldSaleResult.LabelTooLong)
        val byId = products.findByIds(request.lines.map { it.productId }.toSet())
            .associateBy { it.id }
        val saleList = salePriceListOf(request)
        val lines = ArrayList<HeldSaleLine>(request.lines.size)
        for ((index, line) in request.lines.withIndex()) {
            val product = byId[line.productId]
                ?: return TxResult.Rollback(HoldSaleResult.LineRejected(index))
            val list = saleList?.priceOf(product) ?: LinePriceList.default(product)
            lines += heldLineOf(line, product, listPriceOf(line, product, list))
                ?: return TxResult.Rollback(HoldSaleResult.LineRejected(index))
        }
        val sale = NewHeldSale(label, lines, clock.now(), request.priceListId)
        val existing = request.heldSaleId
        val id = if (existing != null && heldSales.replace(existing, sale)) {
            existing
        } else {
            heldSales.insert(sale)
        }
        return TxResult.CommitUncounted(HoldSaleResult.Held(id))
    }

    /**
     * La lista elegida en [request], con lo que se necesita para resolver sus precios, o `null` para
     * la inicial o si no hay con qué leerla: entonces cada línea toma el precio de venta.
     */
    private suspend fun salePriceListOf(request: HoldSaleRequest): SalePriceList? {
        val wanted = request.priceListId ?: return null
        val lists = priceLists ?: return null
        val own = productPrices ?: return null
        val all = lists.findAll().associateBy { it.id }
        val ownPrices = request.lines.map { it.productId }.toSet().associateWith {
            own.findByProduct(it)
        }
        return SalePriceList(wanted, all, ownPrices)
    }

    /**
     * El precio de una unidad entera de la unidad de [line] sin precio escrito, en la lista [list],
     * para convertir un descuento en porcentaje; cero si el producto ya no se vende en esa unidad o
     * la lista no le da precio.
     */
    private suspend fun listPriceOf(
        line: SaleLineRequest,
        product: Product,
        list: LinePriceList,
    ): Money {
        val unitId = line.unitId ?: product.details.baseUnitId
        val conversions = if (unitId == product.details.baseUnitId) {
            emptyMap()
        } else {
            productUnits?.findByProductAndUnit(product.id, unitId)
                ?.let { mapOf(unitId to it.details) }
                .orEmpty()
        }
        return when (val unit = SaleUnits.of(line, product, conversions, list)) {
            is SaleUnitResult.Resolved -> unit.unit.listPrice ?: Money.ZERO
            SaleUnitResult.Unavailable -> Money.ZERO
        }
    }

    /** La línea como se guarda en espera, o `null` si sus cifras no caben en la base. */
    private fun heldLineOf(
        line: SaleLineRequest,
        product: Product,
        listPrice: Money,
    ): HeldSaleLine? {
        if (!Limit.LINE_QUANTITY_MILLI.allows(line.quantity.milli) || line.quantity.milli <= 0) {
            return null
        }
        val written = line.unitPrice
        if (written != null && (written < Money.ZERO || !Limit.UNIT_MONEY.allows(written.minor))) {
            return null
        }
        val discount = when (val asked = line.discount) {
            LineDiscount.None -> Money.ZERO

            // Un monto mayor que la línea se guarda igual: la venta lo marca al retomarla, y lo
            // escrito no se pierde.
            is LineDiscount.Amount -> asked.amount

            is LineDiscount.Percent -> SaleLineRules.gross(
                line.quantity,
                written ?: listPrice,
            ).valueOrNull()?.let { SaleLineRules.discountOf(it, asked) } ?: Money.ZERO
        }
        if (discount < Money.ZERO || !Limit.DOCUMENT_TOTAL.allows(discount.minor)) return null
        return HeldSaleLine(
            productId = product.id,
            unitId = line.unitId ?: product.details.baseUnitId,
            quantity = line.quantity,
            unitPrice = written,
            discount = discount,
        )
    }
}
