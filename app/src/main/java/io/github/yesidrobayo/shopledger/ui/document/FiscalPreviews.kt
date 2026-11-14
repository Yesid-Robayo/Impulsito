package io.github.yesidrobayo.shopledger.ui.document

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// Vistas previas de los pendientes de facturar, con ventas y referencias inventadas.

/** Tres ventas pendientes de un mes: con cliente de nombre largo, con devolución y sin cliente. */
internal fun previewFiscal(
    filter: FiscalMarkStatus = FiscalMarkStatus.PENDING,
    sales: List<FiscalSale> = previewFiscalSales(),
    hasMarks: Boolean = true,
): FiscalUiState = FiscalUiState(
    month = YearMonth(2026, 10),
    today = PreviewSamples.TODAY,
    filter = filter,
    content = FiscalContent.Sales(
        sales = sales,
        total = Money(sales.sumOf { it.total.minor }),
        hasMarks = hasMarks,
    ),
    portalUrl = "https://example.com/portal",
)

internal fun previewFiscalSales(): List<FiscalSale> = listOf(
    FiscalSale(
        saleId = 1_204L,
        businessDate = BusinessDate(LocalDate(2026, 10, 28)),
        total = PreviewSamples.LONG_AMOUNT,
        customerName = PreviewSamples.LONG_NAME,
        status = FiscalMarkStatus.PENDING,
        externalReference = "A-1043",
        hasReturn = false,
    ),
    FiscalSale(
        saleId = 1_198L,
        businessDate = BusinessDate(LocalDate(2026, 10, 27)),
        total = PreviewSamples.SECOND_PRICE,
        customerName = null,
        status = FiscalMarkStatus.PENDING,
        externalReference = null,
        hasReturn = true,
    ),
    FiscalSale(
        saleId = 1_187L,
        businessDate = BusinessDate(LocalDate(2026, 10, 25)),
        total = PreviewSamples.PRICE,
        customerName = null,
        status = FiscalMarkStatus.PENDING,
        externalReference = null,
        hasReturn = false,
    ),
)

@ThemePreviews
@Composable
private fun FiscalListPreview() {
    PreviewTheme { FiscalScreen(previewFiscal(), {}) }
}

@ThemePreviews
@Composable
private fun FiscalSelectingPreview() {
    PreviewTheme {
        FiscalScreen(previewFiscal().copy(selection = setOf(1_204L, 1_187L)), {})
    }
}

@ThemePreviews
@Composable
private fun FiscalFirstUsePreview() {
    PreviewTheme { FiscalScreen(previewFiscal(sales = emptyList(), hasMarks = false), {}) }
}

@Preview(name = "Modo discreto")
@Composable
private fun FiscalDiscreetPreview() {
    PreviewTheme(discreet = true) { FiscalScreen(previewFiscal(), {}) }
}

@Preview(name = "Tableta", widthDp = 1_000, heightDp = 700)
@Composable
private fun FiscalTabletPreview() {
    val state = previewFiscal()
    val sale = previewFiscalSales().first()
    PreviewTheme {
        FiscalScreen(
            state.copy(sheet = FiscalMarkSheet(sale.saleId, sale, "A-1043")),
            {},
        )
    }
}
