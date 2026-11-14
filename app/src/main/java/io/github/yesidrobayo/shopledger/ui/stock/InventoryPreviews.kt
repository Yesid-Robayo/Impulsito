package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate

// Vistas previas del inventario, con productos y montos inventados: la lista por agotarse con
// stock negativo, agotado y bajo, un nombre de 80 caracteres y un valor de trece cifras; la lista
// vacía; el modo discreto; el catálogo vacío y el error; un insumo con su etiqueta, y una búsqueda
// sin resultados.

private val UNIT_SAMPLE =
    MeasureUnit(
        id = 1L,
        systemKey = "UNIT",
        name = null,
        symbol = null,
        kind = UnitKind.COUNT,
        allowsFraction = false,
        archivedAt = null,
    )

private val KILOGRAM_SAMPLE =
    UNIT_SAMPLE.copy(id = 2L, systemKey = "KILOGRAM", allowsFraction = true)

private fun item(id: Long, name: String, status: InventoryStatus, onHand: Long, min: Long) =
    InventoryItem(
        id = id,
        name = name,
        status = status,
        onHand = QuantityMilli(onHand),
        minStock = QuantityMilli(min),
        unit = if (id == 3L) KILOGRAM_SAMPLE else UNIT_SAMPLE,
        // El de kilos es un insumo: lleva su etiqueta en la fila.
        usage = if (id == 3L) ProductUsage.INPUT else ProductUsage.SALE,
    )

internal fun previewInventoryItems(): List<InventoryItem> = listOf(
    item(1L, PreviewSamples.SHORT_NAME, InventoryStatus.NEGATIVE, -2_000L, 5_000L),
    item(2L, PreviewSamples.LONG_NAME, InventoryStatus.OUT, 0L, 3_000L),
    item(3L, PreviewSamples.SECOND_NAME, InventoryStatus.LOW, 1_250L, 2_000L),
    item(4L, PreviewSamples.THIRD_NAME, InventoryStatus.LOW, 5_000L, 5_000L),
)

/**
 * `Revisar`: costo nuevo con margen bajo, pérdida con nombre de 80 caracteres, costo nuevo sin
 * costo, y costo viejo con su fecha.
 */
internal fun previewReviewItems(): List<PriceReviewItem> = listOf(
    PriceReviewItem(
        1L,
        PreviewSamples.SHORT_NAME,
        ProductRowTrailing.Catalog(PreviewSamples.PRICE, Margin.Defined(BasisPoints(1_200L)), true),
        listOf(PriceReviewReason.NEW_COST, PriceReviewReason.LOW_MARGIN),
    ),
    PriceReviewItem(
        2L,
        PreviewSamples.LONG_NAME,
        ProductRowTrailing.Catalog(
            PreviewSamples.SECOND_PRICE,
            Margin.Defined(BasisPoints(-800L)),
            true,
        ),
        listOf(PriceReviewReason.LOW_MARGIN, PriceReviewReason.OLD_COST),
        BusinessDate(LocalDate(2026, 8, 1)),
    ),
    PriceReviewItem(
        3L,
        PreviewSamples.SECOND_NAME,
        ProductRowTrailing.Catalog(
            PreviewSamples.OLD_PRICE,
            Margin.Defined(BasisPoints(10_000L)),
            false,
        ),
    ),
    PriceReviewItem(
        4L,
        PreviewSamples.THIRD_NAME,
        ProductRowTrailing.Catalog(
            PreviewSamples.PRICE,
            Margin.Defined(BasisPoints(4_500L)),
            true,
        ),
        listOf(PriceReviewReason.OLD_COST),
        BusinessDate(LocalDate(2026, 5, 2)),
    ),
)

internal fun previewInventory(
    items: List<InventoryItem> = previewInventoryItems(),
    value: Money? = PreviewSamples.LONG_AMOUNT,
    reviewItems: List<PriceReviewItem> = emptyList(),
) = InventoryContent.Ready(
    lowStockCount = items.size,
    negativeCount = items.count { it.status == InventoryStatus.NEGATIVE },
    value = value,
    archivedValue = Money(48_000L),
    hasStockWithoutCost = true,
    items = items,
    reviewItems = reviewItems,
)

@ThemePreviews
@Composable
private fun InventoryLowStockPreview() {
    PreviewTheme { InventoryScreen(InventoryUiState(content = previewInventory()), {}) }
}

@ThemePreviews
@Composable
private fun InventoryNothingLowPreview() {
    PreviewTheme {
        InventoryScreen(
            InventoryUiState(content = previewInventory(items = emptyList())),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun InventoryNotificationsOffPreview() {
    PreviewTheme {
        InventoryScreen(
            InventoryUiState(content = previewInventory()),
            {},
            notificationsBlocked = true,
        )
    }
}

@ThemePreviews
@Composable
private fun InventoryDiscreetPreview() {
    PreviewTheme(discreet = true) {
        InventoryScreen(InventoryUiState(content = previewInventory()), {})
    }
}

@ThemePreviews
@Composable
private fun InventoryEmptyAndErrorPreview() {
    PreviewTheme { InventoryScreen(InventoryUiState(content = InventoryContent.EmptyCatalog), {}) }
}

@ThemePreviews
@Composable
private fun InventoryReviewPreview() {
    PreviewTheme {
        InventoryScreen(
            InventoryUiState(
                chip = InventoryChip.REVIEW,
                content = previewInventory(reviewItems = previewReviewItems()),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun InventorySearchEmptyPreview() {
    val query = PreviewSamples.SEARCH_QUERY
    PreviewTheme {
        InventoryScreen(
            InventoryUiState(
                content = previewInventory(items = emptyList()).copy(query = query),
                query = query,
            ),
            {},
            onScan = {},
        )
    }
}
