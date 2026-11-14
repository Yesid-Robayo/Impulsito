package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.NewOrder
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.OrderRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult

/**
 * Lo que la persona guarda como pedido: las líneas armadas en la venta, el cliente si eligió uno,
 * la fecha y la dirección de entrega, la nota de entrega, el cobro por envío y la lista de precios
 * con que se armó, nula para la inicial. [orderId] es el pedido que se está editando, o nulo.
 */
data class OrderRequest(
    val lines: List<SaleLineRequest>,
    val customerId: Long? = null,
    val dueDate: BusinessDate? = null,
    val deliveryAddress: String? = null,
    val deliveryNote: String? = null,
    val shippingAmount: Money = Money.ZERO,
    val priceListId: Long? = null,
    val orderId: Long? = null,
)

/** Resultado de [SaveOrderUseCase]. Cualquier rechazo deja todo como estaba. */
sealed interface SaveOrderResult {
    /** El pedido quedó guardado con [orderId], que también es su número visible. */
    data class Saved(val orderId: Long) : SaveOrderResult

    data object EmptyOrder : SaveOrderResult

    data object TooManyLines : SaveOrderResult

    /** La dirección o la nota de entrega pasan de su largo máximo. */
    data object TextTooLong : SaveOrderResult

    /** El cobro por envío es negativo o pasa del tope de un documento. */
    data object InvalidShipping : SaveOrderResult

    data object CustomerUnavailable : SaveOrderResult

    data object PriceListUnavailable : SaveOrderResult

    data class LineRejected(val index: Int, val reason: DocumentLineRejection) : SaveOrderResult

    /** Las líneas con el envío pasan del tope de un documento. */
    data object TotalTooLarge : SaveOrderResult

    /** El pedido que se editaba ya no existe, o ya se entregó o canceló. */
    data object OrderNotOpen : SaveOrderResult

    data object CurrencyNotConfirmed : SaveOrderResult

    data class UnknownTimeZone(val storedZoneId: String) : SaveOrderResult

    data class StorageFailed(val failure: StorageFailure) : SaveOrderResult
}

/**
 * Guarda un pedido, nuevo o editado, en una sola transacción.
 *
 * - **Reserva sin tocar el libro.** Solo escribe el pedido y sus líneas: ningún movimiento de
 *   stock, saldo ni dinero. Lo que reserva sale de sus líneas mientras esté sin entregar.
 * - **Congela precios e impuesto** como una cotización: cada línea se calcula como la venta la
 *   calcularía hoy. Al entregar, la venta se registra con esos precios.
 * - **El envío es ingreso**: entra en el total del pedido y, al entregar, en el ajuste de la venta.
 * - **Editar rehace sus líneas** y conserva cuándo se pidió. Solo se edita uno sin entregar.
 * - La fecha de entrega es opcional, y pasada no cambia nada: el pedido se marca vencido.
 */
class SaveOrderUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val orders: OrderRepository,
    private val customers: CustomerRepository,
    private val clock: Clock,
    products: ProductRepository,
    units: UnitRepository,
    taxRates: TaxRateRepository,
    systemRows: SystemRowRepository,
    productUnits: ProductUnitRepository? = null,
    priceLists: PriceListRepository? = null,
    productPrices: ProductPriceRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)
    private val pricer = DocumentLinePricer(
        products,
        units,
        taxRates,
        systemRows,
        productUnits,
        priceLists,
        productPrices,
    )

    suspend operator fun invoke(request: OrderRequest): SaveOrderResult = try {
        transactions.write { save(request) }
    } catch (failure: StorageFailureException) {
        SaveOrderResult.StorageFailed(failure.failure)
    }

    private suspend fun save(request: OrderRequest): TxResult<SaveOrderResult> {
        shapeRejection(request)?.let { return TxResult.Rollback(it) }
        val current = settings.get()
        val calendar = when (val result = BusinessCalendar.of(current.regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(SaveOrderResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        request.customerId?.let { id ->
            val customer = customers.findById(id)
            if (customer == null || customer.isArchived || customer.isAnonymized) {
                return TxResult.Rollback(SaveOrderResult.CustomerUnavailable)
            }
        }
        val stamp = when (val id = request.orderId) {
            null -> calendar.stamp(now)

            else -> {
                val existing = orders.findById(id)
                if (existing == null || !existing.status.isOpen) {
                    return TxResult.Rollback(SaveOrderResult.OrderNotOpen)
                }
                RecordTime(existing.occurredAt, existing.businessDate)
            }
        }
        val priced = when (
            val result = pricer.price(request.lines, request.priceListId, current.taxes)
        ) {
            is DocumentPricing.Lines -> result

            is DocumentPricing.Rejected ->
                return TxResult.Rollback(SaveOrderResult.LineRejected(result.index, result.reason))

            DocumentPricing.TotalTooLarge -> return TxResult.Rollback(SaveOrderResult.TotalTooLarge)

            DocumentPricing.PriceListUnavailable ->
                return TxResult.Rollback(SaveOrderResult.PriceListUnavailable)
        }
        // Cada parte cabe en el tope de un documento, así que la suma cabe en Long.
        val total = priced.totals.total + request.shippingAmount
        if (!Limit.DOCUMENT_TOTAL.allows(total.minor)) {
            return TxResult.Rollback(SaveOrderResult.TotalTooLarge)
        }
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed ->
                return TxResult.Rollback(SaveOrderResult.CurrencyNotConfirmed)
        }
        val order = NewOrder(
            customerId = request.customerId,
            deliveryAddress = request.deliveryAddress?.trim()?.takeIf { it.isNotEmpty() },
            deliveryNote = request.deliveryNote?.trim()?.takeIf { it.isNotEmpty() },
            shippingAmount = request.shippingAmount,
            dueDate = request.dueDate,
            lines = priced.lines,
            total = total,
            occurredAt = stamp.occurredAt,
            businessDate = stamp.businessDate,
            at = now,
        )
        val id = when (val existing = request.orderId) {
            null -> orders.insert(order)

            else -> if (orders.update(existing, order)) {
                existing
            } else {
                return TxResult.Rollback(SaveOrderResult.OrderNotOpen)
            }
        }
        return TxResult.Commit(SaveOrderResult.Saved(id))
    }

    private fun shapeRejection(request: OrderRequest): SaveOrderResult? = when {
        request.lines.isEmpty() -> SaveOrderResult.EmptyOrder

        request.lines.size > Limit.DOCUMENT_LINES.max -> SaveOrderResult.TooManyLines

        (request.deliveryAddress?.trim()?.length ?: 0) > MAX_TEXT_LENGTH ||
            (request.deliveryNote?.trim()?.length ?: 0) > MAX_TEXT_LENGTH ->
            SaveOrderResult.TextTooLong

        request.shippingAmount < Money.ZERO ||
            !Limit.DOCUMENT_TOTAL.allows(request.shippingAmount.minor) ->
            SaveOrderResult.InvalidShipping

        else -> null
    }

    companion object {
        /** El largo máximo de la dirección y de la nota de entrega, el mismo que acepta la base. */
        const val MAX_TEXT_LENGTH = 200
    }
}
