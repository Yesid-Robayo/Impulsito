package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotEntry
import io.github.yesidrobayo.shopledger.domain.model.stock.LotHolding
import io.github.yesidrobayo.shopledger.domain.model.stock.LotsOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.ExpiryRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotCodeCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.requireId
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Los lotes de la pantalla de lotes: cada lote con su producto, lo que tiene en cada ubicación y
 * cuándo vence, visto desde hoy. Solo de productos sin archivar, como el aviso de vencimiento.
 *
 * Hoy es la fecha de negocio del instante actual, y se vuelve a mirar con cada emisión de
 * `dayChecks`. Si el teléfono no conoce la zona guardada, los lotes salen igual, sin marcar.
 */
class ObserveLotsUseCase(
    private val lots: StockLotRepository,
    private val products: ProductRepository,
    private val calendar: BusinessCalendarSource,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    /** Todos los lotes, o solo los de [productId]. */
    operator fun invoke(
        productId: Long? = null,
        dayChecks: Flow<Unit> = flowOf(Unit),
    ): Flow<LotsOverview> {
        val today = combine(calendar.observe(), dayChecks) { result, _ -> todayOf(result) }
        val noticeDays = settings.observe().map { it.notices.expiryNoticeDays }
            .distinctUntilChanged()
        val tracked = products.observe(ArchiveFilter.ACTIVE)
            .map { list -> list.filter { it.details.tracksLots } }
        return combine(lots.observeAll(), lots.observeHoldings(), tracked, today, noticeDays) {
                all,
                holdings,
                active,
                date,
                days,
            ->
            val byProduct = active.associateBy { it.id }
            val byLot = holdings.groupBy { it.lotId }
            val entries = all
                .filter { productId == null || it.productId == productId }
                .mapNotNull { lot ->
                    byProduct[lot.productId]?.let { entryOf(lot, it, byLot[lot.id], date, days) }
                }
            LotsOverview(entries, days)
        }.distinctUntilChanged()
    }

    private fun todayOf(result: BusinessCalendarResult): BusinessDate? = when (result) {
        is BusinessCalendarResult.Ready -> result.calendar.businessDateOf(clock.now())
        is BusinessCalendarResult.UnknownZone -> null
    }

    private fun entryOf(
        lot: StockLot,
        product: Product,
        holdings: List<LotHolding>?,
        today: BusinessDate?,
        settingsDays: Int,
    ): LotEntry {
        val held = holdings.orEmpty()
        val onHand = held.fold(QuantityMilli.ZERO) { sum, part -> sum + part.onHand }
        val days = ExpiryRules.noticeDaysOf(product.details.expiryNoticeDays, settingsDays)
        return LotEntry(lot, product, onHand, held, ExpiryRules.dueOf(lot.expiryDate, today, days))
    }
}

/**
 * Los movimientos de un lote, del más reciente al más antiguo: lo que dice el detalle del lote en
 * `Últimos movimientos`. El libro no se toca: solo se lee.
 */
class ObserveLotMovementsUseCase(private val ledger: StockLedgerRepository) {
    operator fun invoke(productId: Long, lotId: Long): Flow<List<StockMovementRecord>> = ledger
        .observeByProduct(productId)
        .map { rows ->
            rows.filter { it.movement.key.lotId == lotId }.sortedByDescending { it.id }
        }
        .distinctUntilChanged()
}

/**
 * Los lotes que se pueden elegir para sacar un producto, en el orden de salida, con cuándo vence
 * cada uno. Lo leen la hoja de la línea de una venta y la salida de un ajuste.
 *
 * Una ubicación `null` es la inicial, como en la venta y el ajuste. Devuelve `null` si el producto
 * no existe o no se controla por lotes: entonces no hay nada que elegir.
 */
class LoadLotChoicesUseCase(
    private val lots: StockLotRepository,
    private val products: ProductRepository,
    private val systemRows: SystemRowRepository,
    private val calendar: BusinessCalendarSource,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(productId: Long, locationId: Long?): LotChoices? {
        val product = products.findById(productId)?.takeIf { it.details.tracksLots }
            ?: return null
        val location = locationId ?: systemRows.requireId(SystemRow.MAIN_LOCATION)
        val stocks = lots.findStock(product.id, location)
        val today = when (val result = calendar.current()) {
            is BusinessCalendarResult.Ready -> result.calendar.businessDateOf(clock.now())
            is BusinessCalendarResult.UnknownZone -> null
        }
        val days = ExpiryRules.noticeDaysOf(
            product.details.expiryNoticeDays,
            settings.get().notices.expiryNoticeDays,
        )
        return LotChoices(
            lots = LotRules.exitOrder(stocks).map {
                LotChoice(it, ExpiryRules.dueOf(it.expiryDate, today, days))
            },
            unassigned = stocks.firstOrNull { it.isUnassigned },
        )
    }
}

/** Resultado de [CorrectLotUseCase]. */
sealed interface CorrectLotResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : CorrectLotResult

    /** El lote no existe, o es el sin asignar, que no tiene código ni vencimiento. */
    data object NotFound : CorrectLotResult

    /** El código no vale: en blanco o demasiado largo. */
    data class InvalidCode(val problem: LotCodeCheck) : CorrectLotResult

    /** Otro lote de [productName] ya tiene ese código, que es único dentro del producto. */
    data class CodeRepeated(val productName: String) : CorrectLotResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : CorrectLotResult
}

/**
 * Corrige el código o el vencimiento de un lote con código. El lote es un dato de catálogo, no del
 * libro: corregirlo no mueve stock ni costo, y las filas del libro lo siguen nombrando por su id.
 *
 * El vencimiento admite fechas pasadas, porque un lote puede llegar vencido. Si cambia, el aviso
 * vuelve a quedar pendiente para la fecha nueva. Va en una sola transacción.
 */
class CorrectLotUseCase(
    private val transactions: TransactionRunner,
    private val lots: StockLotRepository,
    private val products: ProductRepository,
) {
    suspend operator fun invoke(
        lotId: Long,
        rawCode: String,
        expiryDate: BusinessDate?,
    ): CorrectLotResult {
        val code = when (val check = LotRules.checkCode(rawCode)) {
            is LotCodeCheck.Valid -> check.code

            LotCodeCheck.Blank, is LotCodeCheck.TooLong -> return CorrectLotResult.InvalidCode(
                check,
            )
        }
        return try {
            transactions.write { write(lotId, code, expiryDate) }
        } catch (failure: StorageFailureException) {
            CorrectLotResult.Failed(failure.failure)
        }
    }

    private suspend fun write(
        lotId: Long,
        code: String,
        expiryDate: BusinessDate?,
    ): TxResult<CorrectLotResult> {
        val lot = lots.findById(lotId)
        if (lot == null || lot.isUnassigned) return TxResult.Rollback(CorrectLotResult.NotFound)
        val other = lots.findByCode(lot.productId, code)
        if (other != null && other.id != lot.id) {
            val name = products.findById(lot.productId)?.details?.name.orEmpty()
            return TxResult.Rollback(CorrectLotResult.CodeRepeated(name))
        }
        if (lot.code == code && lot.expiryDate == expiryDate) {
            return TxResult.Rollback(CorrectLotResult.Saved)
        }
        lots.correct(lotId, code, expiryDate)
        return TxResult.Commit(CorrectLotResult.Saved)
    }
}
