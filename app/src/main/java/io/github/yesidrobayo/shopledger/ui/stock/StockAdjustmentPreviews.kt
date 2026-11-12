package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentSubject
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas del ajuste de stock, con productos y montos inventados: el conteo del ejemplo de
// las reglas, una salida que deja el stock negativo con nombre de 80 caracteres y monto largo, otro
// motivo con entrada, el inventario inicial, un producto sin costo y el modo discreto.

private val UNIT =
    MeasureUnit(
        id = 1L,
        systemKey = "UNIT",
        name = null,
        symbol = null,
        kind = UnitKind.COUNT,
        allowsFraction = false,
        archivedAt = null,
    )

internal fun previewSubject(
    name: String = PreviewSamples.SHORT_NAME,
    onHand: Long = 12_000L,
    average: Long = 1_355L,
    value: Long = 16_260L,
    acceptsOpening: Boolean = false,
) = StockAdjustmentSubject(
    productId = 1L,
    name = name,
    unit = UNIT,
    stockMode = ProductStockMode.TRACKED,
    isArchived = false,
    minStock = QuantityMilli(5_000L),
    cost = CostState(QuantityMilli(onHand), Money(average), Money(value)),
    acceptsOpeningBalance = acceptsOpening,
)

private fun preview(left: Long, difference: Long, value: Long) =
    AdjustmentPreview(QuantityMilli(left), QuantityMilli(difference), Money(value))

private fun ready(subject: StockAdjustmentSubject = previewSubject()) =
    StockAdjustmentContent.Ready(subject)

@ThemePreviews
@Composable
private fun CountPreview() {
    PreviewTheme {
        StockAdjustmentScreen(
            StockAdjustmentUiState(
                content = ready(),
                mode = AdjustmentMode.COUNT,
                quantityText = "10",
                preview = preview(10_000L, -2_000L, -2_710L),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun NegativeExitPreview() {
    val subject = previewSubject(PreviewSamples.LONG_NAME, 1_000L, 999_999_999L, 999_999_999L)
    PreviewTheme {
        StockAdjustmentScreen(
            StockAdjustmentUiState(
                content = ready(subject),
                mode = AdjustmentMode.CHANGE,
                reason = StockAdjustmentReason.EXPIRED,
                quantityText = "3",
                preview =
                    AdjustmentPreview(
                        QuantityMilli(-2_000L),
                        QuantityMilli(-3_000L),
                        Money(-2_999_999_997L),
                    ),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun OtherInPreview() {
    PreviewTheme {
        StockAdjustmentScreen(
            StockAdjustmentUiState(
                content = ready(),
                mode = AdjustmentMode.CHANGE,
                reason = StockAdjustmentReason.OTHER,
                increase = true,
                quantityText = "1",
                errors = mapOf(AdjustmentField.NOTE to AdjustmentProblem.NoteRequired),
                preview = preview(13_000L, 1_000L, 1_355L),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun OpeningPreview() {
    PreviewTheme {
        StockAdjustmentScreen(
            StockAdjustmentUiState(
                content = ready(previewSubject(onHand = 0L, value = 0L, acceptsOpening = true)),
                mode = AdjustmentMode.OPENING,
                quantityText = "24",
                unitCostText = "1200",
                preview = preview(24_000L, 24_000L, 28_800L),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun NoCostDiscreetPreview() {
    PreviewTheme(discreet = true) {
        StockAdjustmentScreen(
            StockAdjustmentUiState(
                content = ready(previewSubject(average = 0L, value = 0L)),
                mode = AdjustmentMode.CHANGE,
                quantityText = "2",
                saveFailed = true,
                preview = preview(10_000L, -2_000L, 0L),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun LoadingAndUnavailablePreview() {
    PreviewTheme {
        StockAdjustmentScreen(StockAdjustmentUiState(StockAdjustmentContent.Unavailable), {})
    }
}
