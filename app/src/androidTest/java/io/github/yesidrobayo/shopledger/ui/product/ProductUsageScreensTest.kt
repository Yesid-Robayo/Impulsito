package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: un insumo puro no muestra nada de venta, ni en su detalle ni en su fila, y las
 * filas de insumos llevan su etiqueta dentro de la fila, que TalkBack lee con el nombre. Lo que
 * también se vende se ve como siempre. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ProductUsageScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int): String = resources.getString(id)

    private fun showDetail(usage: ProductUsage) {
        val s = ProductDetailSamples
        val overview = s.overview(s.product(usage = usage, replacementCost = 4_000_00L))
        val state =
            ProductDetailUiState(ProductDetailContent.Ready(overview, s.pricing(overview)))
        compose.setContent {
            PreviewTheme {
                ProductDetailScreen(state, {})
            }
        }
    }

    @Test
    fun anInputDetailShowsItsCostsAndNothingOfSelling() {
        showDetail(ProductUsage.INPUT)

        compose.onNodeWithText(text(R.string.term_average_cost)).assertExists()
        compose.onNodeWithText(text(R.string.product_replacement_cost)).assertExists()
        compose.onNodeWithText(text(R.string.product_usage_input)).assertExists()
        for (selling in SELLING) {
            compose.onNodeWithText(text(selling)).assertDoesNotExist()
        }
    }

    @Test
    fun aDetailThatIsAlsoSoldKeepsItsPriceAndMargin() {
        showDetail(ProductUsage.BOTH)

        compose.onNodeWithText(text(R.string.product_usage_both)).assertExists()
        for (selling in SELLING) {
            compose.onNodeWithText(text(selling)).assertExists()
        }
    }

    @Test
    fun inputRowsCarryTheirTagAndAPureInputShowsItsCost() {
        val unit = MeasureUnit(
            id = 1L,
            systemKey = "UNIT",
            name = null,
            symbol = null,
            kind = UnitKind.COUNT,
            allowsFraction = false,
            archivedAt = null,
        )
        fun row(id: Long, name: String, usage: ProductUsage, price: Money?) = ProductListItem(
            id = id,
            name = name,
            stock = ProductListStock.Normal(QuantityMilli(4_000L)),
            unit = unit,
            trailing = ProductRowTrailing.Catalog(price, Margin.NoData, hasCost = true),
            archived = false,
            usage = usage,
            cost = Money(1_250L),
        )
        val rows = listOf(
            row(1L, INPUT_NAME, ProductUsage.INPUT, price = null),
            row(2L, BOTH_NAME, ProductUsage.BOTH, price = Money(3_000L)),
            row(3L, SALE_NAME, ProductUsage.SALE, price = Money(3_000L)),
        )
        compose.setContent {
            PreviewTheme {
                ProductListScreen(
                    ProductListUiState(
                        content =
                            ProductListContent.Products(listOf(ProductListSection(null, rows))),
                    ),
                    {},
                )
            }
        }

        // La etiqueta está dentro de la fila: la fila, un solo foco, dice el nombre y el uso.
        compose.onNode(hasText(INPUT_NAME) and hasText(text(R.string.product_usage_input)))
            .assertExists()
        compose.onNode(hasText(BOTH_NAME) and hasText(text(R.string.product_usage_both)))
            .assertExists()
        compose.onNode(hasText(SALE_NAME) and hasText(text(R.string.product_usage_input)))
            .assertDoesNotExist()
        // El insumo puro dice su costo, sin `Sin precio`: no tiene precio que poner.
        compose.onNode(hasText(INPUT_NAME) and hasText(text(R.string.term_average_cost)))
            .assertExists()
        compose.onAllNodesWithText(text(R.string.notice_no_price)).assertCountEquals(0)
        compose.onNode(hasText(BOTH_NAME) and hasText(text(R.string.term_average_cost)))
            .assertDoesNotExist()
    }

    private companion object {
        val SELLING = listOf(
            R.string.product_sale_price,
            R.string.term_margin,
            R.string.product_profit_if_replaced,
            R.string.product_tab_prices,
            R.string.product_sell_this,
        )
        const val INPUT_NAME = "Harina de prueba"
        const val BOTH_NAME = "Azúcar de prueba"
        const val SALE_NAME = "Pan de prueba"
    }
}
