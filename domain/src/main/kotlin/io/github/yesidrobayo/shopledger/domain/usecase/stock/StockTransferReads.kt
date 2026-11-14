package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransfer
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StockTransferRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

/**
 * Una transferencia en la lista, con cuántos productos movió: un producto con lotes guarda una
 * línea por lote, pero cuenta una vez.
 */
data class StockTransferSummary(val transfer: StockTransfer, val productCount: Int)

/** Lo que se lee de la lista de transferencias. */
sealed interface StockTransfersRead {
    /**
     * Este teléfono no conoce la zona del negocio: no hay un hoy desde el cual contar los días.
     */
    data object UnknownZone : StockTransfersRead

    /**
     * Las transferencias del tramo leído, de la más nueva a la más antigua. [hasOlder] dice si
     * puede haber otras antes del tramo; si es falso, la lista ya está completa.
     */
    data class Ready(val transfers: List<StockTransferSummary>, val hasOlder: Boolean) :
        StockTransfersRead
}

/**
 * Las transferencias de los últimos días, por tramos: con años de historia, la lista no espera a
 * leerlo todo. Vuelve a emitir con cada transferencia nueva o anulada, y si cambia la zona.
 *
 * Una transferencia necesita dos ubicaciones, y una de ellas siempre es una creada por la persona.
 * Así, no puede haber ninguna antes del día en que se creó la primera de esas: ahí termina la
 * lista, sin tener que pedir un tramo vacío para saberlo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveStockTransfersUseCase(
    private val transfers: StockTransferRepository,
    private val locations: LocationRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    /** Las de los últimos [days] días de negocio, hoy incluido. [days] es al menos 1. */
    operator fun invoke(days: Int): Flow<StockTransfersRead> {
        require(days >= 1) { "El tramo tiene al menos un día" }
        return calendar.observe().flatMapLatest { result ->
            when (result) {
                is BusinessCalendarResult.UnknownZone -> flowOf(StockTransfersRead.UnknownZone)

                is BusinessCalendarResult.Ready -> {
                    val today = result.calendar.businessDateOf(clock.now())
                    val start = today.plusDays(1 - days)
                    // Un día más al final: la lista abierta pasada la medianoche sigue viendo las
                    // transferencias nuevas, que ya llevan la fecha de mañana.
                    combine(
                        transfers.observeIn(start, today.plusDays(1)),
                        locations.observe(ArchiveFilter.ACTIVE),
                        locations.observe(ArchiveFilter.ARCHIVED),
                    ) { found, active, archived ->
                        Triple(found, firstPossibleDate(active + archived, result), start)
                    }.mapLatest { (found, first, from) ->
                        StockTransfersRead.Ready(
                            transfers = found.map {
                                val products = transfers.findLines(it.id)
                                    .distinctBy { saved -> saved.line.productId }
                                StockTransferSummary(it, products.size)
                            },
                            hasOlder = first != null && first < from,
                        )
                    }
                }
            }
        }.distinctUntilChanged()
    }

    /** El día en que se creó la primera ubicación de la persona, o `null` si no hay ninguna. */
    private fun firstPossibleDate(
        all: List<Location>,
        result: BusinessCalendarResult.Ready,
    ): BusinessDate? = all.filterNot { it.isInitial }
        .minOfOrNull { it.createdAt }
        ?.let { result.calendar.businessDateOf(it) }
}

/**
 * Una línea de una transferencia, lista para mostrar: el producto, su unidad, cuánto se movió y,
 * en un producto con lotes, de qué [lot].
 */
data class StockTransferDetailLine(
    val productId: Long,
    val product: Product?,
    val unit: MeasureUnit?,
    val quantity: QuantityMilli,
    val lot: StockLot? = null,
)

/**
 * Un producto que quedaría en negativo en el destino si se anula la transferencia: lo que
 * quedaría,
 * [remaining], con su unidad. Pasa cuando ya se vendió allí lo que llegó.
 */
data class VoidShortfall(
    val product: Product,
    val unit: MeasureUnit?,
    val remaining: QuantityMilli,
)

/**
 * Una transferencia abierta desde la lista: sus líneas y, si está activa, lo que su anulación
 * dejaría en negativo en el destino. Anular igual se permite: solo se avisa.
 */
data class StockTransferDetail(
    val transfer: StockTransfer,
    val lines: List<StockTransferDetailLine>,
    val voidShortfalls: List<VoidShortfall>,
)

/** Lee una transferencia para su hoja de detalle. No escribe nada. */
class ReadStockTransferUseCase(
    private val transfers: StockTransferRepository,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val stock: LocationStockSource,
    private val lots: StockLotRepository? = null,
) {
    /** La transferencia [transferId], o `null` si ya no existe. */
    suspend operator fun invoke(transferId: Long): StockTransferDetail? {
        val transfer = transfers.findById(transferId) ?: return null
        val lines = transfers.findLines(transferId)
        val byId = products.findByIds(lines.map { it.line.productId }.toSet())
            .associateBy { it.id }
        val unitsById = byId.values.map { it.details.baseUnitId }.distinct()
            .mapNotNull { units.findById(it) }
            .associateBy { it.id }
        val detailLines = lines.map { saved ->
            val product = byId[saved.line.productId]
            StockTransferDetailLine(
                productId = saved.line.productId,
                product = product,
                unit = product?.let { unitsById[it.details.baseUnitId] },
                quantity = saved.line.quantity,
                lot = saved.line.lotId?.let { lots?.findById(it) },
            )
        }
        val shortfalls = if (transfer.voiding != null) {
            emptyList()
        } else {
            shortfalls(transfer.content.toLocationId, detailLines)
        }
        return StockTransferDetail(transfer, detailLines, shortfalls)
    }

    /**
     * Lo que quedaría en el destino de cada producto al anular, sumando sus líneas, solo de los
     * que
     * quedarían por debajo de cero.
     */
    private suspend fun shortfalls(
        destinationId: Long,
        lines: List<StockTransferDetailLine>,
    ): List<VoidShortfall> = lines.groupBy { it.productId }.mapNotNull { (productId, group) ->
        val first = group.first()
        val product = first.product ?: return@mapNotNull null
        val moved = group.fold(QuantityMilli.ZERO) { sum, line -> sum + line.quantity }
        val remaining = stock.onHandAt(productId, destinationId) - moved
        if (remaining < QuantityMilli.ZERO) VoidShortfall(product, first.unit, remaining) else null
    }
}
