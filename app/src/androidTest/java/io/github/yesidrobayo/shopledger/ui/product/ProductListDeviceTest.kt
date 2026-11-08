package io.github.yesidrobayo.shopledger.ui.product

import android.util.Log
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import kotlin.time.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lista de productos en el teléfono, con 5000 productos inventados: cuánto tarda cada búsqueda
 * al escribir, y que la lista llegue hasta el último producto y responda a sus toques.
 *
 * Un emulador no es el teléfono de referencia: los tiempos se anotan para comparar y no se exige un
 * tope estricto. Solo registra conteos y duraciones, nunca un nombre.
 */
@RunWith(AndroidJUnit4::class)
class ProductListDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val units = mapOf(SAMPLE_UNIT.id to SAMPLE_UNIT)
    private val catalog = (1..PRODUCTS).map(::sampleProduct).sortedBy { it.details.normalizedName }

    @Test
    fun eachKeystrokeSearchesFiveThousandProductsQuickly() {
        // La primera vuelta calienta el código: no cuenta.
        ProductListItems.build(catalog, units, "")
        for (term in listOf("a", "ar", "arr", "arro", "arroz", "arroz 4")) {
            val times = List(ROUNDS) {
                val mark = TimeSource.Monotonic.markNow()
                val content = ProductListItems.build(catalog, units, term)
                val elapsed = mark.elapsedNow().inWholeMicroseconds
                val found = (content as ProductListContent.Products).sections.flatMap { it.items }
                assertTrue(found.isNotEmpty())
                assertTrue(found.all { TextNormalizer.normalize(it.name).contains(term) })
                elapsed
            }.sorted()
            val median = times[ROUNDS / 2]
            val longest = times.last()
            Log.i(TAG, "búsqueda de ${term.length} letras: mediana $median us, máximo $longest us")
            // Holgado a propósito: el tope de la búsqueda se mide en el teléfono de referencia.
            assertTrue(median < LIMIT_MICROS)
        }
    }

    @Test
    fun theListReachesTheLastProductAndOpensIt() {
        var opened: Long? = null
        val content = ProductListItems.build(catalog, units, "")
        compose.setContent {
            PreviewTheme {
                ProductListScreen(
                    uiState = ProductListUiState(content = content),
                    onAction = { if (it is ProductListAction.OpenProduct) opened = it.productId },
                )
            }
        }
        val rows = (content as ProductListContent.Products).sections
        // Con 5000 productos la lista lleva un encabezado por inicial.
        assertTrue(rows.size > 1)
        val positions = rows.sumOf { it.items.size + 1 }
        val last = rows.last().items.last()

        val mark = TimeSource.Monotonic.markNow()
        compose.onNode(hasScrollAction()).performScrollToIndex(positions - 1)
        compose.waitForIdle()
        Log.i(TAG, "desplazar a la fila $positions: ${mark.elapsedNow().inWholeMilliseconds} ms")
        compose.onNodeWithText(last.name).performClick()

        compose.runOnIdle { assertEquals(last.id, opened) }
    }

    @Test
    fun noResultsOffersToCreateWithWhatWasTyped() {
        var created: String? = null
        compose.setContent {
            PreviewTheme {
                ProductListScreen(
                    uiState = ProductListUiState("acite", ProductListContent.NoResults("acite")),
                    onAction = { if (it is ProductListAction.CreateProduct) created = it.name },
                )
            }
        }

        val action = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.empty_search_action)
        compose.onAllNodesWithText("acite", substring = true).onFirst().assertExists()
        compose.onNodeWithText(action).performClick()

        compose.runOnIdle { assertEquals("acite", created) }
    }

    private companion object {
        const val TAG = "ShopLedgerTest"
        const val PRODUCTS = 5_000
        const val ROUNDS = 11
        const val LIMIT_MICROS = 500_000L
    }
}
