package io.github.yesidrobayo.shopledger.ui.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import kotlin.time.Instant

/**
 * Listas y productos inventados para las vistas previas y las pruebas de la pantalla: la inicial,
 * una fija con precios propios, una relativa con un nombre de 40 caracteres y una archivada.
 */
object PriceListSamples {
    const val DEFAULT_ID = 1L
    const val WHOLESALE_ID = 2L
    const val SPECIAL_ID = 3L
    const val OLD_ID = 4L

    private const val GENERAL_NAME = "Lista general"
    private const val WHOLESALE_NAME = "Mayoristas"
    private const val SPECIAL_NAME = "Clientes especiales de temporada alta 24"
    private const val OLD_NAME = "Feria"

    val default = PriceListSummary(
        PriceList(DEFAULT_ID, "DEFAULT", null, PriceListPricing.Fixed, null),
        GENERAL_NAME,
        ownPrices = 0,
        baseName = null,
    )

    val wholesale = PriceListSummary(
        PriceList(WHOLESALE_ID, null, WHOLESALE_NAME, PriceListPricing.Fixed, null),
        WHOLESALE_NAME,
        ownPrices = 12,
        baseName = null,
    )

    val special = PriceListSummary(
        PriceList(
            SPECIAL_ID,
            null,
            SPECIAL_NAME,
            PriceListPricing.Relative(DEFAULT_ID, BasisPoints(-1_000L)),
            null,
        ),
        SPECIAL_NAME,
        ownPrices = 0,
        baseName = GENERAL_NAME,
    )

    val old = PriceListSummary(
        PriceList(
            OLD_ID,
            null,
            OLD_NAME,
            PriceListPricing.Fixed,
            Instant.fromEpochMilliseconds(5L),
        ),
        OLD_NAME,
        ownPrices = 1,
        baseName = null,
    )

    /** Productos de la lista relativa: uno calculado, uno con monto largo y uno sin costo. */
    val rows: List<ListProductRow> = listOf(
        ListProductRow(
            productId = 7L,
            name = PreviewSamples.SHORT_NAME,
            salePrice = Money(5_000L),
            price = Money(4_500L),
            origin = ListPriceOrigin.CALCULATED,
            margin = Margin.Defined(BasisPoints(1_560L)),
            hasCost = true,
        ),
        ListProductRow(
            productId = 8L,
            name = PreviewSamples.LONG_NAME,
            salePrice = Money(1_234_567_890L),
            price = Money(1_111_111_101L),
            origin = ListPriceOrigin.OWN,
            margin = Margin.Defined(BasisPoints(1_820L)),
            hasCost = true,
        ),
        ListProductRow(
            productId = 9L,
            name = PreviewSamples.SECOND_NAME,
            salePrice = Money(11_000L),
            price = Money(11_000L),
            origin = ListPriceOrigin.SALE_PRICE,
            margin = Margin.NoData,
            hasCost = false,
        ),
    )

    /** La lista con sus cuatro listas y ninguna abierta. */
    val lists = PriceListsUiState(
        loading = false,
        active = listOf(default, wholesale, special),
        archived = listOf(old),
    )

    /** El detalle de la relativa abierto. */
    val detail = lists.copy(selectedId = SPECIAL_ID, rows = rows, rowsLoading = false)

    /** Un producto con costo de 3800 y margen objetivo de 30 %, sin impuesto. */
    val pricing: ProductPricing = ProductPricing.draft(
        context = PricingContext(
            BasisPoints(3_000L),
            emptyMap(),
            TaxMode.NONE,
            null,
            emptyMap(),
            Money.ZERO,
        ),
        cost = ExactUnitCost.perUnit(Money(3_800L)),
        replacementCost = Money.ZERO,
        productTarget = null,
        categoryId = null,
        taxRateId = null,
    )

    /** Solo existe la inicial. */
    val empty = PriceListsUiState(loading = false, active = listOf(default))
}
