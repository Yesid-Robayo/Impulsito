package io.github.yesidrobayo.shopledger.ui.count

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSession
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountLineView
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountSessionSummary
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountSessionView
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import io.github.yesidrobayo.shopledger.ui.location.LocationSamples
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Datos inventados para las vistas previas y las pruebas del conteo: dos conteos abiertos, uno por
 * ubicación, dos anteriores y una sesión con un producto contado igual, uno sin contar, uno por
 * peso con faltante y uno con sobrante.
 */
internal object CountSamples {
    private val AT = Instant.fromEpochMilliseconds(0L)

    val DAY: BusinessDate = BusinessDate(LocalDate(2026, 10, 28))

    val CATEGORY = Category(
        5L,
        CategoryDetails(PreviewSamples.CATEGORY_B, PreviewSamples.CATEGORY_B.lowercase(), null),
        AT,
        AT,
    )

    private val UNIT_ID = LocationSamples.UNIT.id

    val RICE = LocationSamples.product(7L, PreviewSamples.SHORT_NAME, UNIT_ID, 8_000L)
    val OIL = LocationSamples.product(9L, PreviewSamples.SECOND_NAME, UNIT_ID, 12_000L)
    val CHEESE = LocationSamples.CHEESE
    val SOAP = LocationSamples.product(10L, PreviewSamples.THIRD_NAME, UNIT_ID, 0L)

    val OPEN = session(1L, CountSessionStatus.OPEN, LocationSamples.MAIN.id, categoryId = null)
    val OPEN_STALL =
        session(2L, CountSessionStatus.OPEN, LocationSamples.STALL.id, categoryId = CATEGORY.id)
    val APPLIED = session(3L, CountSessionStatus.APPLIED, LocationSamples.MAIN.id, CATEGORY.id)
    val CANCELLED = session(4L, CountSessionStatus.CANCELLED, LocationSamples.STALL.id, null)

    val OVERVIEW = CountOverview(
        open = listOf(
            CountSessionSummary(OPEN, LocationSamples.MAIN, null, 12, 48, 2),
            CountSessionSummary(OPEN_STALL, LocationSamples.STALL, CATEGORY, 0, 3, 0),
        ),
        finished = listOf(
            CountSessionSummary(APPLIED, LocationSamples.MAIN, CATEGORY, 30, 30, 5),
            CountSessionSummary(CANCELLED, LocationSamples.STALL, null, 4, 20, 1),
        ),
        showsLocations = true,
        activeLocations = listOf(LocationSamples.MAIN, LocationSamples.STALL),
    )

    /** Quien usa una sola ubicación y todavía no contó nada. */
    val EMPTY = CountOverview(
        open = emptyList(),
        finished = emptyList(),
        showsLocations = false,
        activeLocations = listOf(LocationSamples.MAIN),
    )

    /** El arroz, igual; el aceite, sin contar; el queso, con faltante; el jabón, con sobrante. */
    fun view(session: CountSession = OPEN) = CountSessionView(
        session = session,
        location = LocationSamples.MAIN,
        category = null,
        lines = listOf(
            line(session, 101L, RICE, LocationSamples.UNIT, expected = 8_000L, counted = 8_000L),
            line(session, 102L, OIL, LocationSamples.UNIT, expected = 12_000L, counted = null),
            line(session, 103L, CHEESE, LocationSamples.KILOGRAM, 2_350L, counted = 2_100L),
            line(session, 104L, SOAP, LocationSamples.UNIT, expected = 0L, counted = 3_000L),
        ),
    )

    /** Las filas de [view] como las arma la sesión, con lo guardado escrito tal cual. */
    fun rows(moved: Set<Long> = emptySet()): List<CountRowUi> = view().lines.map { item ->
        CountRowUi(
            lineId = item.line.id,
            name = item.product?.details?.name.orEmpty(),
            unit = item.unit,
            expected = item.line.expected,
            counted = item.line.counted,
            text = item.line.counted?.let { quantityInput(it) }.orEmpty(),
            moved = item.line.productId in moved,
        )
    }

    /** La sesión mientras se cuenta, a ciegas o no. */
    fun counting(blind: Boolean = true) = CountSessionUiState(
        loading = false,
        session = OPEN.copy(isBlind = blind),
        location = LocationSamples.MAIN,
        showsLocation = true,
        rows = rows(),
        counted = 3,
        total = 4,
    )

    /** La revisión, con el aceite sin contar y el jabón movido desde que se abrió. */
    fun reviewing() = counting().copy(
        rows = rows(moved = setOf(SOAP.id)),
        reviewing = true,
        review = CountReviewUi(
            adjustments = 2,
            surplus = Money(11_400L),
            shortage = Money(950L),
            movedCount = 1,
        ),
    )

    fun session(id: Long, status: CountSessionStatus, locationId: Long, categoryId: Long?) =
        CountSession(
            id = id,
            status = status,
            isBlind = true,
            locationId = locationId,
            categoryId = categoryId,
            note = null,
            startedAt = AT,
            finishedAt = AT.takeIf { status != CountSessionStatus.OPEN },
            businessDate = DAY,
        )

    private fun line(
        session: CountSession,
        id: Long,
        product: Product,
        unit: MeasureUnit,
        expected: Long,
        counted: Long?,
    ) = CountLineView(
        CountSessionLine(
            id = id,
            sessionId = session.id,
            productId = product.id,
            lotId = null,
            expected = QuantityMilli(expected),
            counted = counted?.let { QuantityMilli(it) },
        ),
        product,
        unit,
    )

    /** Lo guardado como lo escribe la sesión, con los números de las vistas previas. */
    private fun quantityInput(quantity: QuantityMilli): String =
        fixedPointInputText(quantity.milli, MILLI_DECIMALS, previewFormats().quantity.inputSymbols)

    private const val MILLI_DECIMALS = 3
}
