package io.github.yesidrobayo.shopledger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.ui.destination.DestinationChoiceScreen
import io.github.yesidrobayo.shopledger.ui.destination.DestinationChoiceUiState
import io.github.yesidrobayo.shopledger.ui.expense.ExpenseForm
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesScreen
import io.github.yesidrobayo.shopledger.ui.expense.previewExpenses
import io.github.yesidrobayo.shopledger.ui.purchase.NewPurchaseScreen
import io.github.yesidrobayo.shopledger.ui.purchase.NewPurchaseUiState
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseLineUi
import io.github.yesidrobayo.shopledger.ui.sale.CHECKOUT_LINES
import io.github.yesidrobayo.shopledger.ui.sale.CheckoutHandoff
import io.github.yesidrobayo.shopledger.ui.sale.CheckoutScreen
import io.github.yesidrobayo.shopledger.ui.sale.CheckoutViewModel
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleAction
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleScreen
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleViewModel
import io.github.yesidrobayo.shopledger.ui.sale.SaleDraft
import io.github.yesidrobayo.shopledger.ui.sale.SaleNumberFormat
import io.github.yesidrobayo.shopledger.ui.sale.checkoutCatalog
import io.github.yesidrobayo.shopledger.ui.sale.saleCatalog
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los cuatro flujos que pide la revisión de accesibilidad, medidos en el teléfono con la letra que
 * tenga, normal o al 200 %: vender y cobrar, comprar, registrar un gasto y elegir dónde guardar las
 * copias. En cada escena todo lo tocable mide 48 dp y tiene nombre, y ningún texto se sale de su caja.
 * Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val audit = A11yAudit(compose)
    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)

    /**
     * La escala de la letra del teléfono, en por ciento. Se corre una vez con 1.0 y otra con 2.0 en
     * `font_scale`: así Android aplica su escala no lineal, la misma que ve la persona.
     */
    private val percent: Int
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return (context.resources.configuration.fontScale * PERCENT).toInt()
        }

    /** Dibuja una sola vez; cada escena reemplaza lo que se ve. */
    private fun start() {
        compose.setContent { PreviewTheme { key(scene) { scene?.invoke() } } }
    }

    /** Mide [content] y junta sus fallas, para ver todas las de un flujo en una sola corrida. */
    private fun measure(
        problems: MutableList<String>,
        name: String,
        content: @Composable () -> Unit,
    ) {
        scene = content
        problems += audit.problems().map { "$name al $percent %: $it" }
    }

    @Test
    fun sellingAndCheckingOutPass() {
        start()
        val problems = mutableListOf<String>()
        for ((name, actions) in saleScenes()) {
            val model = saleModel()
            actions.forEach(model::onAction)
            measure(problems, name) {
                val state by model.uiState.collectAsState()
                NewSaleScreen(state, model::onAction)
            }
        }
        val checkout = checkoutModel()
        measure(problems, "cobro") {
            val state by checkout.uiState.collectAsState()
            CheckoutScreen(state, checkout::onAction, onBack = {})
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun aPurchasePasses() {
        start()
        val problems = mutableListOf<String>()
        measure(problems, "compra") { NewPurchaseScreen(purchase(), onAction = {}) }
        measure(problems, "compra vacía") {
            NewPurchaseScreen(
                NewPurchaseUiState(loading = false, today = TODAY, weekStartDay = DayOfWeek.MONDAY),
                onAction = {},
            )
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun anExpensePasses() {
        start()
        val problems = mutableListOf<String>()
        val list = previewExpenses()
        measure(problems, "gastos") { ExpensesScreen(list, onAction = {}) }
        measure(problems, "gasto nuevo") {
            ExpensesScreen(
                list.copy(
                    form = ExpenseForm(amountText = "6000", categoryId = 9, description = "Bolsas"),
                ),
                onAction = {},
            )
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun choosingWhereToKeepBackupsPasses() {
        start()
        val problems = mutableListOf<String>()
        measure(problems, "elegir destino") {
            DestinationChoiceScreen(
                DestinationChoiceUiState(ConnectedDestination.NONE, firstRun = true),
                onAction = {},
            )
        }
        measure(problems, "cambiar destino") {
            DestinationChoiceScreen(
                DestinationChoiceUiState(
                    ConnectedDestination.WEBDAV,
                ),
                onAction = {
                },
            )
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun saleScenes(): List<Pair<String, List<NewSaleAction>>> {
        val selling =
            listOf(
                NewSaleAction.AddProduct(1L),
                NewSaleAction.Increment(1L),
                NewSaleAction.AddProduct(2L),
                NewSaleAction.AddProduct(4L),
                NewSaleAction.QuantityShortcut(QuantityMilli(500L)),
            )
        return listOf(
            "venta" to emptyList(),
            "venta con productos" to selling,
            "carrito" to selling + NewSaleAction.OpenCart,
        )
    }

    private fun saleModel(): NewSaleViewModel {
        lateinit var model: NewSaleViewModel
        compose.runOnIdle {
            model =
                NewSaleViewModel(
                    catalogSource = { flowOf(saleCatalog()) },
                    registerSale = { RegisterSaleResult.Registered(1L) },
                    reactivate = { true },
                    savedState = SavedStateHandle(),
                    numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
                    computation = Dispatchers.Default,
                )
        }
        return model
    }

    private fun checkoutModel(): CheckoutViewModel {
        lateinit var model: CheckoutViewModel
        compose.runOnIdle {
            model =
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
        return model
    }

    private fun purchase() = NewPurchaseUiState(
        loading = false,
        supplierName = "Distribuidora inventada",
        today = TODAY,
        weekStartDay = DayOfWeek.MONDAY,
        reference = "F-0042",
        lines =
            listOf(
                PurchaseLineUi(
                    key = 1L,
                    productId = 1L,
                    name = "Jabón de avena",
                    unit = UNIT,
                    quantityText = "24",
                    amountText = "417",
                    byTotal = false,
                    lineTotal = Money(10_008L),
                    derived = Money(10_008L),
                    costBefore = Money(380L),
                    costAfter = Money(401L),
                ),
            ),
        total = Money(10_008L),
    )

    private companion object {
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        const val PERCENT = 100
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
        val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    }
}
