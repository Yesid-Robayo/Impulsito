package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate

// Vistas previas de la devolución a proveedor, con datos inventados: el ejemplo de la regla, un
// nombre de 80 caracteres, un monto de trece cifras, una línea que pasa de lo comprado, una que deja
// el stock negativo y la variante vacía.

private val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)

private fun previewState(): SupplierReturnUiState {
    val s = PreviewSamples
    return SupplierReturnUiState(
        loading = false,
        withPurchase = true,
        supplierName = s.LONG_NAME,
        supplierLocked = true,
        origin = ReturnOriginUi(318L, BusinessDate(LocalDate(2026, 10, 28))),
        lines = listOf(
            SupplierReturnLineUi(
                key = 1L,
                name = s.SHORT_NAME,
                unit = UNIT,
                quantityText = "2",
                unitCost = Money(1_400L),
                lineTotal = Money(2_800L),
                costBefore = Money(1_200L),
                costAfter = Money(1_160L),
            ),
            SupplierReturnLineUi(
                key = 2L,
                name = s.LONG_NAME,
                unit = UNIT,
                quantityText = "5",
                error = SupplierReturnLineError.AboveRemaining(QuantityMilli(3_000L)),
            ),
            SupplierReturnLineUi(
                key = 3L,
                name = s.SHORT_NAME,
                unit = UNIT,
                quantityText = "12",
                unitCost = Money(102_880_657_510L),
                lineTotal = s.LONG_AMOUNT,
                costBefore = Money(1_200L),
                costAfter = Money(1_200L),
                leftAfter = QuantityMilli(-2_000L),
            ),
        ),
        resolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
        balanceAfter = Money(-2_800L),
        note = "Llegó golpeada",
        total = s.LONG_AMOUNT,
    )
}

@ThemePreviews
@Composable
private fun SupplierReturnLinesPreview() {
    PreviewTheme { SupplierReturnScreen(previewState(), {}) }
}

@ThemePreviews
@Composable
private fun SupplierReturnEmptyPreview() {
    PreviewTheme { SupplierReturnScreen(SupplierReturnUiState(loading = false), {}) }
}

@Preview(name = "Tableta", widthDp = 1000, heightDp = 640)
@Composable
private fun SupplierReturnTabletPreview() {
    PreviewTheme { SupplierReturnScreen(previewState(), {}) }
}

@Preview(name = "Falla al registrar, crédito sin proveedor")
@Composable
private fun SupplierReturnFailedPreview() {
    PreviewTheme {
        SupplierReturnScreen(
            previewState().copy(
                supplierName = null,
                supplierLocked = false,
                creditNeedsSupplier = true,
                balanceAfter = null,
                notice = SupplierReturnNotice.RegisterFailed(outOfSpace = false),
            ),
            {},
        )
    }
}
