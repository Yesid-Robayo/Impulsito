package io.github.yesidrobayo.shopledger.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.format.CsvFormatFactory
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesScreen
import io.github.yesidrobayo.shopledger.ui.expense.previewExpenses
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf
import io.github.yesidrobayo.shopledger.ui.purchase.NewPurchaseScreen
import io.github.yesidrobayo.shopledger.ui.purchase.NewPurchaseUiState
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseLineUi
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
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * La matriz de configuraciones sobre el núcleo, en el teléfono: venta, cobro, compra, gastos y
 * `Números`, con la moneda, los decimales, el formato y el idioma de la interfaz de cada fila. El
 * total del cobro se ve con los mismos enteros que se guardan, escrito como lo escribe el formato de
 * la fila, y ningún texto ni monto se sale de su caja, tampoco al 200 % si el teléfono tiene esa
 * letra. El CSV escribe el monto con el separador decimal que da el teléfono para la región, sin
 * miles ni símbolo. Datos inventados.
 */
@RunWith(Parameterized::class)
class RegionalMatrixScreensTest(private val row: RegionalMatrixRow) {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val audit = A11yAudit(compose)
    private val region = row.regionOr(RegionalMatrix.INVENTED_DEVICE)
    private val language = row.uiLanguageTag ?: "en"
    private val formats = businessFormatsOf(row.regionalSettings(), Locale.forLanguageTag(language))
    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)

    private val percent: Int
        get() {
            val scale = instrumentation.targetContext.resources.configuration.fontScale
            return (scale * PERCENT).toInt()
        }

    /** Dibuja una vez, con los textos en el idioma de la fila y sus formatos. */
    private fun start() {
        val base = instrumentation.targetContext
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(language))
        val context = base.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
            ) {
                PreviewTheme {
                    CompositionLocalProvider(LocalAppFormats provides formats) {
                        key(scene) { scene?.invoke() }
                    }
                }
            }
        }
    }

    private fun measure(
        problems: MutableList<String>,
        name: String,
        content: @Composable () -> Unit,
    ) {
        scene = content
        problems += audit.problems().map { "$row, $name al $percent %: $it" }
    }

    @Test
    fun theCoreScreensFollowTheRowWithoutCuts() {
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
        // Dos aceites y un café con 10 %: 38.410 en unidad menor, con los decimales de la fila.
        val total = formats.money.format(Money(CHECKOUT_TOTAL))
        // La cifra se lee en el texto del botón o en lo que dice TalkBack del total.
        val withTotal =
            hasText(total, substring = true) or hasContentDescription(total, substring = true)
        val shown =
            runCatching {
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodes(withTotal).fetchSemanticsNodes().isNotEmpty()
                }
            }.isSuccess
        if (!shown) problems += "$row: el total $total no se ve; se ve ${amountsShown()}"

        measure(problems, "compra") { NewPurchaseScreen(purchase(), onAction = {}) }
        measure(problems, "gastos") { ExpensesScreen(previewExpenses(), onAction = {}) }
        measure(problems, "números") {
            StatisticsScreen(previewStatisticsContent(), onAction = {})
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theCsvWritesTheAmountWithTheRegionDecimalSeparator() {
        val locale = requireNotNull(FormatLocale.of(region.formatLocaleTag))
        val format =
            CsvFormat(
                region.csvSeparator,
                CsvFormatFactory.decimalSeparator(locale),
                row.currencyDecimals,
            )
        val zone = requireNotNull(BusinessTimeZone.of(region.businessTimeZoneId))
        val writer = CsvRowWriter(format, zone)

        // El mismo total, escrito a mano con los decimales y el separador de la fila.
        val digits = CHECKOUT_TOTAL.toString().padStart(row.currencyDecimals + 1, '0')
        val whole = digits.dropLast(row.currencyDecimals)
        val fraction = digits.takeLast(row.currencyDecimals)
        val expected =
            if (row.currencyDecimals ==
                0
            ) {
                whole
            } else {
                "$whole${region.decimalSeparator.char}$fraction"
            }
        assertEquals("$row", expected, writer.cell(CsvCell.Amount(Money(CHECKOUT_TOTAL))))
    }

    /** Lo que se lee con cifras, para decir qué salió en lugar del total esperado. */
    private fun amountsShown(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.filter { text -> text.any(Char::isDigit) }

    private fun saleModel(): NewSaleViewModel = compose.runOnIdle {
        NewSaleViewModel(
            catalogSource = { flowOf(saleCatalog()) },
            registerSale = { RegisterSaleResult.Registered(1L) },
            reactivate = { true },
            savedState = SavedStateHandle(),
            numbers = numbers(),
            computation = Dispatchers.Default,
        )
    }

    private fun checkoutModel(): CheckoutViewModel = compose.runOnIdle {
        CheckoutViewModel(
            catalogSource = { MutableStateFlow(checkoutCatalog()) },
            registerSale = { RegisterSaleResult.Registered(1L) },
            findImage = { null },
            savedState = SavedStateHandle(),
            numbers = numbers(),
            offered = { CheckoutHandoff.Offered(SaleDraft(CHECKOUT_LINES, 1L), false) },
            computation = Dispatchers.Default,
        )
    }

    /** Una compra de dos líneas, con costos que cambian, como la de la galería de compras. */
    private fun purchase() = NewPurchaseUiState(
        loading = false,
        supplierName = "Distribuidora inventada",
        today = BusinessDate(LocalDate(2026, 11, 2)),
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

    /** Los símbolos con que la fila lee lo que se escribe: montos, cantidades y porcentajes. */
    private fun numbers(): SaleNumberFormat {
        val symbols = region.numberSymbols
        return SaleNumberFormat(symbols, row.currencyDecimals, symbols, symbols)
    }

    companion object {
        const val CHECKOUT_TOTAL = 38_410L
        const val TIMEOUT_MS = 5_000L
        const val PERCENT = 100
        val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<RegionalMatrixRow> = RegionalMatrix.ROWS
    }
}
