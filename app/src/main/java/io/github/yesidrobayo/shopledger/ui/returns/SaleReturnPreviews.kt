package io.github.yesidrobayo.shopledger.ui.returns

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate

// Vistas previas de la devolución de una venta, con datos inventados: una línea con algo ya
// devuelto, un nombre de 80 caracteres que no vuelve al inventario, una línea por peso, un monto de
// trece cifras y la variante vacía.

private val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
private val KILOGRAM = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)

private fun previewState(): SaleReturnUiState {
    val s = PreviewSamples
    return SaleReturnUiState(
        loading = false,
        saleId = 1_204L,
        saleDate = BusinessDate(LocalDate(2026, 10, 28)),
        lines = listOf(
            SaleReturnLineUi(
                lineId = 1L,
                name = s.SHORT_NAME,
                unit = UNIT,
                sold = QuantityMilli(3_000L),
                returnedBefore = QuantityMilli(1_000L),
                remaining = QuantityMilli(2_000L),
                quantity = QuantityMilli(1_000L),
                restock = true,
                archived = false,
            ),
            SaleReturnLineUi(
                lineId = 2L,
                name = s.LONG_NAME,
                unit = UNIT,
                sold = QuantityMilli(2_000L),
                returnedBefore = QuantityMilli.ZERO,
                remaining = QuantityMilli(2_000L),
                quantity = QuantityMilli(2_000L),
                restock = false,
                archived = true,
            ),
            SaleReturnLineUi(
                lineId = 3L,
                name = s.SECOND_NAME,
                unit = KILOGRAM,
                sold = QuantityMilli(1_250L),
                returnedBefore = QuantityMilli.ZERO,
                remaining = QuantityMilli(1_250L),
                quantity = QuantityMilli.ZERO,
                restock = true,
                archived = false,
            ),
        ),
        countsOn = BusinessDate(LocalDate(2026, 11, 13)),
        withPoints = true,
        itemCount = 2,
        total = s.LONG_AMOUNT,
        net = s.LONG_AMOUNT,
        showsTax = true,
    )
}

@ThemePreviews
@Composable
private fun SaleReturnLinesPreview() {
    PreviewTheme { SaleReturnScreen(previewState(), {}) }
}

@ThemePreviews
@Composable
private fun SaleReturnStoreCreditPreview() {
    PreviewTheme {
        SaleReturnScreen(
            previewState().copy(
                resolution = ReturnResolution.STORE_CREDIT,
                customer = ReturnCustomerUi(
                    7L,
                    PreviewSamples.LONG_NAME,
                    Money(4_000L),
                    Money(-8_500L),
                ),
                customerLocked = true,
                creditSale = true,
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun SaleReturnEmptyPreview() {
    PreviewTheme {
        SaleReturnScreen(SaleReturnUiState(loading = false, nothingLeft = true, saleId = 1L), {})
    }
}

@Preview(name = "Tableta", widthDp = 1000, heightDp = 640)
@Composable
private fun SaleReturnTabletPreview() {
    PreviewTheme { SaleReturnScreen(previewState(), {}) }
}

@Preview(name = "Falla al registrar, saldo a favor sin cliente, nada elegido")
@Composable
private fun SaleReturnFailedPreview() {
    PreviewTheme {
        SaleReturnScreen(
            previewState().copy(
                resolution = ReturnResolution.STORE_CREDIT,
                needsCustomer = true,
                noneSelected = true,
                notice = SaleReturnNotice.RegisterFailed(outOfSpace = false),
            ),
            {},
        )
    }
}
