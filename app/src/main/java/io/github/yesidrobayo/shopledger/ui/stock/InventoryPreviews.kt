package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas del inventario, con productos y montos inventados: la lista por agotarse con
// stock negativo, agotado y bajo, un nombre de 80 caracteres y un valor de trece cifras; la lista
// vacía; el modo discreto; el catálogo vacío y el error.

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
    )

internal fun previewInventoryItems(): List<InventoryItem> = listOf(
    item(1L, PreviewSamples.SHORT_NAME, InventoryStatus.NEGATIVE, -2_000L, 5_000L),
    item(2L, PreviewSamples.LONG_NAME, InventoryStatus.OUT, 0L, 3_000L),
    item(3L, PreviewSamples.SECOND_NAME, InventoryStatus.LOW, 1_250L, 2_000L),
    item(4L, PreviewSamples.THIRD_NAME, InventoryStatus.LOW, 5_000L, 5_000L),
)

internal fun previewInventory(
    items: List<InventoryItem> = previewInventoryItems(),
    value: Money? = PreviewSamples.LONG_AMOUNT,
) = InventoryContent.Ready(
    lowStockCount = items.size,
    negativeCount = items.count { it.status == InventoryStatus.NEGATIVE },
    value = value,
    archivedValue = Money(48_000L),
    hasStockWithoutCost = true,
    items = items,
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
