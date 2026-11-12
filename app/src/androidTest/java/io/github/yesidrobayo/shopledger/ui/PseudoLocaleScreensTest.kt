package io.github.yesidrobayo.shopledger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.ui.destination.DestinationChoiceScreen
import io.github.yesidrobayo.shopledger.ui.destination.DestinationChoiceUiState
import io.github.yesidrobayo.shopledger.ui.expense.ExpenseForm
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesScreen
import io.github.yesidrobayo.shopledger.ui.expense.previewExpenses
import io.github.yesidrobayo.shopledger.ui.sale.CHECKOUT_LINES
import io.github.yesidrobayo.shopledger.ui.sale.CheckoutHandoff
import io.github.yesidrobayo.shopledger.ui.sale.CheckoutScreen
import io.github.yesidrobayo.shopledger.ui.sale.CheckoutViewModel
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleScreen
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleViewModel
import io.github.yesidrobayo.shopledger.ui.sale.SaleDraft
import io.github.yesidrobayo.shopledger.ui.sale.SaleNumberFormat
import io.github.yesidrobayo.shopledger.ui.sale.checkoutCatalog
import io.github.yesidrobayo.shopledger.ui.sale.saleCatalog
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsScreen
import io.github.yesidrobayo.shopledger.ui.stats.previewStatisticsContent
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las seudolocalizaciones de debug sobre las pantallas del núcleo: inglés estirado y con acentos
 * (`en-XA`) y árabe de derecha a izquierda (`ar-XB`), puestos como idioma de la app. Un texto escrito
 * en el código no se transforma, y uno que no cabe se sale de su caja. En `en-XA` cada recurso llega entre corchetes, así que un
 * texto de la interfaz sin ellos es texto fijo. Los nombres de productos y los montos son datos de la
 * escena, no textos de la app, y no cuentan. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class PseudoLocaleScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val audit = A11yAudit(compose)
    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)

    /**
     * El idioma de la app, que pone quien corre la prueba con `cmd locale set-app-locales`: así las
     * hojas, que viven en su propia ventana, también lo usan, como en la app.
     */
    private val tag: String =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
            .locales[0]
            .toLanguageTag()

    private fun start() {
        compose.setContent { PreviewTheme { key(scene) { scene?.invoke() } } }
    }

    private fun measure(
        problems: MutableList<String>,
        name: String,
        content: @Composable () -> Unit,
    ) {
        scene = content
        problems += audit.problems().map { "$tag, $name: $it" }
        if (tag == EXPANDED) {
            // Prueba de que la seudolocalización llegó a la pantalla: algún texto viene entre corchetes.
            if (texts().none { '[' in it }) problems += "$tag, $name: nada seudolocalizado"
            problems += fixedTexts().map { "$tag, $name: texto fijo \"$it\"" }
        }
    }

    /**
     * Los textos que no pasaron por la seudolocalización: con letras, sin corchetes y sin ser datos de
     * la escena. Un monto, una fecha o el nombre de un producto llegan tal cual, y se dejan fuera.
     */
    private fun fixedTexts(): List<String> = texts().filter { text ->
        LETTERS.containsMatchIn(text) && '[' !in text && DATA.none { it in text }
    }.distinct()

    /** Todos los textos que se ven en la escena. */
    private fun texts(): List<String> {
        compose.waitForIdle()
        val withText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        return compose.onAllNodes(withText, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
    }

    @Test
    fun theCoreScreensHaveNoFixedTextAndNoCuts() {
        // Sin una seudolocalización como idioma de la app no hay nada que medir: en la suite común
        // se salta.
        assumeTrue("Con la app en $EXPANDED o $MIRRORED", tag == EXPANDED || tag == MIRRORED)
        start()
        val problems = mutableListOf<String>()
        val sale = saleModel()
        measure(problems, "venta") {
            val state by sale.uiState.collectAsState()
            NewSaleScreen(state, sale::onAction)
        }
        val checkout = checkoutModel()
        measure(problems, "cobro") {
            val state by checkout.uiState.collectAsState()
            CheckoutScreen(state, checkout::onAction, onBack = {})
        }
        val expenses = previewExpenses()
        measure(problems, "gastos") { ExpensesScreen(expenses, onAction = {}) }
        measure(problems, "gasto nuevo") {
            val form = ExpenseForm(amountText = "6000", categoryId = 9)
            ExpensesScreen(expenses.copy(form = form), onAction = {})
        }
        measure(problems, "números") {
            StatisticsScreen(previewStatisticsContent(), onAction = {})
        }
        measure(problems, "elegir destino") {
            val state = DestinationChoiceUiState(ConnectedDestination.NONE, firstRun = true)
            DestinationChoiceScreen(state, onAction = {})
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun saleModel(): NewSaleViewModel = compose.runOnIdle {
        NewSaleViewModel(
            catalogSource = { flowOf(saleCatalog()) },
            registerSale = { RegisterSaleResult.Registered(1L) },
            reactivate = { true },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
            computation = Dispatchers.Default,
        )
    }

    private fun checkoutModel(): CheckoutViewModel = compose.runOnIdle {
        CheckoutViewModel(
            catalogSource = { MutableStateFlow(checkoutCatalog()) },
            registerSale = { RegisterSaleResult.Registered(1L) },
            findImage = { null },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
            offered = { CheckoutHandoff.Offered(SaleDraft(CHECKOUT_LINES, 1L), false) },
            computation = Dispatchers.Default,
        )
    }

    companion object {
        const val EXPANDED = "en-XA"
        const val MIRRORED = "ar-XB"
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        val LETTERS = Regex("[A-Za-z]{2,}")

        /**
         * Lo que la escena trae como dato y no como texto de la app: nombres de productos, de
         * métodos y de gastos de muestra, la moneda, y las fechas, que escribe ICU con el formato
         * regional y no pasan por los recursos.
         */
        val DATA =
            listOf(
                "Aceite", "Arroz", "Café", "Jabón", "Bolsas", "Frijol", "Panela", "Leche",
                "Huevos", "Queso", "Azúcar", "Corte de cabello", "Muestra", "Efectivo", "Tarjeta",
                "XTS", "ccc", "Nov", "Oct",
            )
    }
}
