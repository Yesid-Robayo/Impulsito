package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.DayOfWeek

// Vistas previas de la compra, con datos inventados: un nombre de 80 caracteres, un monto de trece
// cifras, un costo muy distinto del anterior y la variante vacía.

private val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)

private fun previewState(): NewPurchaseUiState {
    val s = PreviewSamples
    return NewPurchaseUiState(
        loading = false,
        supplierName = s.LONG_NAME,
        reference = "F-0042",
        lines = listOf(
            PurchaseLineUi(
                key = 1L,
                productId = 1L,
                name = s.SHORT_NAME,
                unit = UNIT,
                quantityText = "24",
                amountText = "417",
                byTotal = false,
                lineTotal = Money(10_008L),
                derived = Money(10_008L),
                costBefore = Money(380L),
                costAfter = Money(401L),
            ),
            PurchaseLineUi(
                key = 2L,
                productId = 2L,
                name = s.LONG_NAME,
                unit = UNIT,
                quantityText = "12",
                amountText = "1234567890123",
                byTotal = true,
                lineTotal = s.LONG_AMOUNT,
                derived = Money(102_880_657_510L),
                costBefore = Money(1_200L),
                costAfter = Money(102_880_657_510L),
                costDiffersFrom = Money(1_200L),
            ),
        ),
        total = s.LONG_AMOUNT,
    )
}

@ThemePreviews
@Composable
private fun NewPurchaseLinesPreview() {
    PreviewTheme { NewPurchaseScreen(previewState(), {}) }
}

@ThemePreviews
@Composable
private fun NewPurchaseEmptyPreview() {
    PreviewTheme { NewPurchaseScreen(NewPurchaseUiState(loading = false), {}) }
}

@Preview(name = "Tableta", widthDp = 1000, heightDp = 640)
@Composable
private fun NewPurchaseTabletPreview() {
    PreviewTheme { NewPurchaseScreen(previewState(), {}) }
}

@Preview(name = "Con lotes")
@Composable
private fun NewPurchaseLotsPreview() {
    val today = requireNotNull(BusinessDate.parse("2026-11-14"))
    val state = previewState()
    val lines = state.lines.mapIndexed { index, line ->
        line.copy(
            lot = if (index == 0) {
                PurchaseLotUi(code = "A-118", expiry = today.plusDays(20))
            } else {
                PurchaseLotUi()
            },
        )
    }
    PreviewTheme {
        NewPurchaseScreen(
            state.copy(lines = lines, today = today, weekStartDay = DayOfWeek.MONDAY),
            {},
        )
    }
}

@Preview(name = "Falla al registrar")
@Composable
private fun NewPurchaseFailedPreview() {
    PreviewTheme {
        NewPurchaseScreen(
            previewState().copy(notice = PurchaseNotice.RegisterFailed(outOfSpace = false)),
            {},
        )
    }
}

@Preview(name = "Registrada")
@Composable
private fun NewPurchaseRegisteredPreview() {
    PreviewTheme {
        NewPurchaseScreen(
            NewPurchaseUiState(
                loading = false,
                registered = PurchaseSummary(
                    purchaseId = 1L,
                    total = Money(10_008L),
                    costs = listOf(
                        CostChangeUi(1L, PreviewSamples.SHORT_NAME, Money(380L), Money(401L)),
                    ),
                ),
            ),
            {},
        )
    }
}
