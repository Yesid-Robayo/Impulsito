package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotDue
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate

// Vistas previas de la venta, con datos inventados: un nombre de 80 caracteres, un monto de trece
// cifras, un producto vendido de más y la variante vacía.

private val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)

private fun previewTiles(): List<SaleTile> {
    val s = PreviewSamples
    return listOf(
        SaleTile(1L, s.SHORT_NAME, s.PRICE, TileStock.Normal(s.STOCK), UNIT, line = null),
        SaleTile(
            2L,
            s.SECOND_NAME,
            s.SECOND_PRICE,
            TileStock.Low(s.LOW_STOCK),
            UNIT,
            TileLine(s.TWO, removesLine = false, canIncrement = true, oversell = null),
        ),
        SaleTile(
            3L,
            s.LONG_NAME,
            s.LONG_AMOUNT,
            TileStock.Negative(s.NEGATIVE_STOCK),
            UNIT,
            line = null,
        ),
        SaleTile(
            4L,
            s.THIRD_NAME,
            null,
            TileStock.Out,
            UNIT,
            TileLine(s.ONE, removesLine = true, canIncrement = true, oversell = s.NEGATIVE_STOCK),
        ),
    )
}

private fun previewState(): NewSaleUiState {
    val s = PreviewSamples
    val lines = listOf(
        CartLine(
            productId = 2L,
            name = s.SECOND_NAME,
            unit = UNIT,
            quantity = s.TWO,
            unitPrice = s.SECOND_PRICE,
            discount = Money.ZERO,
            total = Money(25_000L),
            issue = null,
            removesLine = false,
            canIncrement = true,
        ),
    )
    return NewSaleUiState(
        content = SaleContent.Products(previewTiles(), searching = false),
        cart = SaleCartState(lines = lines, itemCount = 2L, total = Money(25_000L)),
        payment = SalePaymentState(
            methods = listOf(
                SaleMethod(1L, "CASH", null, hasQr = false),
                SaleMethod(2L, "CARD", null, hasQr = false),
                SaleMethod(3L, null, s.METHOD_WALLET, hasQr = true),
            ),
            selectedId = 1L,
        ),
    )
}

/**
 * La hoja de una línea con lotes: el lote que saldría ya venció, así que la línea y la hoja lo
 * avisan, y `Cambiar lote` ofrece el siguiente. Lotes inventados.
 */
internal fun lotLineState(): NewSaleUiState {
    val base = previewState()
    val expired = BusinessDate(LocalDate(2026, 10, 30))
    val later = BusinessDate(LocalDate(2026, 11, 28))
    val line = base.cart.lines.single().copy(expiredLot = expired)
    val choices = LotChoices(
        listOf(
            LotChoice(
                LotStock(31L, "A-1", expired, false, QuantityMilli(2_000L)),
                LotDue(expired, -15, soon = true),
            ),
            LotChoice(
                LotStock(32L, "A-2", later, false, QuantityMilli(6_000L)),
                LotDue(later, 14, soon = true),
            ),
        ),
        null,
    )
    return base.copy(
        cart = base.cart.copy(lines = listOf(line)),
        lots = mapOf(line.productId to choices),
        sheet = SaleSheet.Line(
            LineEditor(
                productId = line.productId,
                name = line.name,
                unit = line.unit,
                quantityText = "2",
                priceText = "12500",
                discountMode = DiscountMode.AMOUNT,
                discountText = "",
            ),
        ),
    )
}

@ThemePreviews
@Composable
private fun NewSaleLotLinePreview() {
    PreviewTheme { NewSaleScreen(lotLineState(), {}) }
}

@ThemePreviews
@Composable
private fun NewSaleSellingPreview() {
    PreviewTheme { NewSaleScreen(previewState(), {}) }
}

@ThemePreviews
@Composable
private fun NewSaleFrequentPreview() {
    PreviewTheme {
        NewSaleScreen(
            NewSaleUiState(content = SaleContent.Products(previewTiles(), searching = false)),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun NewSaleEmptyPreview() {
    PreviewTheme { NewSaleScreen(NewSaleUiState(content = SaleContent.EmptyCatalog), {}) }
}

@Preview(name = "Sin resultados")
@Composable
private fun NewSaleNoResultsPreview() {
    val query = PreviewSamples.SEARCH_QUERY
    PreviewTheme {
        NewSaleScreen(NewSaleUiState(content = SaleContent.NoResults(query), query = query), {})
    }
}

@Preview(name = "Tableta", widthDp = 1000, heightDp = 640)
@Composable
private fun NewSaleTabletPreview() {
    PreviewTheme { NewSaleScreen(previewState(), {}) }
}

@Preview(name = "Falla al registrar")
@Composable
private fun NewSaleFailedPreview() {
    PreviewTheme {
        NewSaleScreen(previewState().copy(status = RegisterStatus.FAILED), {})
    }
}

@Preview(name = "Cargando")
@Composable
private fun NewSaleLoadingPreview() {
    PreviewTheme { NewSaleScreen(NewSaleUiState(), {}) }
}
