package io.github.yesidrobayo.shopledger.ui.reorder

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de la reposición sugerida, con datos inventados: el ejemplo de la regla, un
// nombre de 80 caracteres, un costo de trece cifras, una caja de 24, un agotado, uno sin ventas
// recientes y los dos vacíos.

private val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
private val BOX = MeasureUnit(3L, "BOX", null, null, UnitKind.COUNT, false, null)

internal fun previewReorder(): ReorderUiState {
    val s = PreviewSamples
    return ReorderUiState(
        loading = false,
        coverText = "30",
        groups = listOf(
            ReorderGroupUi(
                supplierId = 10L,
                supplierName = s.LONG_NAME,
                lines = listOf(
                    line(1L, s.SHORT_NAME, onHand = 0, days = 0, qty = 45_000)
                        .copy(soldOut = true),
                    line(2L, s.LONG_NAME, onHand = 12_000, days = 8, qty = 38_000),
                    line(3L, s.SECOND_NAME, onHand = 6_000, days = 4, qty = 48_000).copy(
                        purchaseUnit = BOX,
                        purchaseFactor = 24_000,
                    ),
                ),
                estimatedCost = s.LONG_AMOUNT,
            ),
            ReorderGroupUi(
                supplierId = null,
                supplierName = null,
                lines = listOf(
                    line(4L, s.THIRD_NAME, onHand = 2_000, days = null, qty = 0)
                        .copy(recentSales = false),
                ),
                estimatedCost = Money.ZERO,
            ),
        ),
    )
}

private fun line(id: Long, name: String, onHand: Long, days: Long?, qty: Long) = ReorderLineUi(
    productId = id,
    name = name,
    unit = UNIT,
    onHand = QuantityMilli(onHand),
    daysLeft = days,
    recentSales = true,
    soldOut = false,
    quantity = QuantityMilli(qty),
)

@ThemePreviews
@Composable
private fun ReorderPreview() {
    PreviewTheme { ReorderScreen(previewReorder(), {}) }
}

@ThemePreviews
@Composable
private fun ReorderNeedsSalesPreview() {
    PreviewTheme {
        ReorderScreen(
            ReorderUiState(loading = false, coverText = "30", empty = ReorderEmpty.NEEDS_SALES),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ReorderNothingPreview() {
    PreviewTheme {
        ReorderScreen(
            ReorderUiState(
                loading = false,
                coverText = "30",
                empty = ReorderEmpty.NOTHING_TO_RESTOCK,
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ReorderFailedPreview() {
    PreviewTheme { ReorderScreen(ReorderUiState(loading = false, loadFailed = true), {}) }
}
