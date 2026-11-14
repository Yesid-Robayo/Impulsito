package io.github.yesidrobayo.shopledger.ui.lot

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.stock.LotEntry
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.LotHolding
import io.github.yesidrobayo.shopledger.domain.model.stock.LotsOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.stock.ExpiryRules
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.location.LocationSamples
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Lotes inventados para las vistas previas y las pruebas de la pantalla de lotes: un queso por
 * kilo con tres lotes, uno vencido, en dos ubicaciones, y un aceite con un lote sin vencimiento y
 * su lote sin asignar en negativo. Hoy es el 14 de noviembre de 2026, con 30 días de aviso.
 */
object LotSamples {
    val TODAY: BusinessDate = BusinessDate(LocalDate(2026, 11, 14))
    const val NOTICE_DAYS = 30
    private val CREATED = Instant.fromEpochMilliseconds(0L)

    val CHEESE: Product = LocationSamples.product(
        8L,
        PreviewSamples.LONG_NAME,
        LocationSamples.KILOGRAM.id,
        onHand = 9_500L,
    )
    val OIL: Product = LocationSamples.product(
        9L,
        PreviewSamples.SHORT_NAME,
        LocationSamples.UNIT.id,
        onHand = 10_000L,
    )

    /** A-118 vence en 3 días: 4,5 kg en la inicial y 1 kg en el puesto. */
    val SOON: LotEntry = entry(
        1L,
        CHEESE,
        "A-118",
        "2026-11-17",
        LotHolding(1L, LocationSamples.MAIN.id, QuantityMilli(4_500L)),
        LotHolding(1L, LocationSamples.STALL.id, QuantityMilli(1_000L)),
    )

    /** A-121 vence en 14 días. */
    val LATER: LotEntry = entry(
        2L,
        CHEESE,
        "A-121",
        "2026-11-28",
        LotHolding(2L, LocationSamples.MAIN.id, QuantityMilli(2_000L)),
    )

    /** L-0042 venció hace 4 días y aún tiene 2 kg. */
    val EXPIRED: LotEntry = entry(
        3L,
        CHEESE,
        "L-0042",
        "2026-11-10",
        LotHolding(3L, LocationSamples.MAIN.id, QuantityMilli(2_000L)),
    )

    /** 7741 no vence. */
    val UNDATED: LotEntry = entry(
        4L,
        OIL,
        "7741",
        null,
        LotHolding(4L, LocationSamples.MAIN.id, QuantityMilli(12_000L)),
    )

    /** El aceite vendió 2 und más de lo recibido con lote. */
    val UNASSIGNED: LotEntry = entry(
        5L,
        OIL,
        null,
        null,
        LotHolding(5L, LocationSamples.MAIN.id, QuantityMilli(-2_000L)),
    )

    val OVERVIEW = LotsOverview(listOf(SOON, LATER, EXPIRED, UNDATED, UNASSIGNED), NOTICE_DAYS)

    val EMPTY = LotsOverview(emptyList(), NOTICE_DAYS)

    val LOCATIONS = listOf(LocationSamples.MAIN, LocationSamples.STALL)

    val UNITS = listOf(LocationSamples.UNIT, LocationSamples.KILOGRAM)

    /** Los últimos movimientos del lote A-118, como los muestra su hoja. */
    val MOVEMENTS = listOf(
        LotMovementItem(12L, StockMovementType.SALE, TODAY, QuantityMilli(-500L)),
        LotMovementItem(
            9L,
            StockMovementType.PURCHASE,
            BusinessDate(LocalDate(2026, 11, 2)),
            QuantityMilli(6_000L),
        ),
    )

    /** El estado listo con [filter], como lo arma el ViewModel. */
    fun ready(
        filter: LotFilter = LotFilter.EXPIRING,
        overview: LotsOverview = OVERVIEW,
        sheet: LotSheet? = null,
    ): LotsUiState = LotsUiState(
        filter = filter,
        content = LotsContent.Ready(
            LotsReadyBuilder.build(
                overview,
                filter,
                UNITS.associateBy { it.id },
                LOCATIONS,
                TODAY,
                DayOfWeek.MONDAY,
            ),
        ),
        sheet = sheet,
    )

    private fun entry(
        id: Long,
        product: Product,
        code: String?,
        expiry: String?,
        vararg holdings: LotHolding,
    ): LotEntry {
        val date = expiry?.let { requireNotNull(BusinessDate.parse(it)) }
        val lot = StockLot(
            id = id,
            productId = product.id,
            code = code,
            isUnassigned = code == null,
            expiryDate = date,
            purchaseLineId = null,
            expiryNotifiedAt = null,
            createdAt = CREATED,
            closedAt = null,
        )
        val onHand = holdings.fold(QuantityMilli.ZERO) { sum, part -> sum + part.onHand }
        val due = ExpiryRules.dueOf(date, TODAY, NOTICE_DAYS)
        return LotEntry(lot, product, onHand, holdings.toList(), due)
    }
}
