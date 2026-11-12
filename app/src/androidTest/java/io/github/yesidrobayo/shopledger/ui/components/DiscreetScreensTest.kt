package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.usecase.settings.AppLockSettings
import io.github.yesidrobayo.shopledger.ui.product.ProductDetailContent
import io.github.yesidrobayo.shopledger.ui.product.ProductDetailSamples
import io.github.yesidrobayo.shopledger.ui.product.ProductDetailScreen
import io.github.yesidrobayo.shopledger.ui.product.ProductDetailUiState
import io.github.yesidrobayo.shopledger.ui.purchase.NewPurchaseScreen
import io.github.yesidrobayo.shopledger.ui.purchase.NewPurchaseUiState
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseLineUi
import io.github.yesidrobayo.shopledger.ui.settings.SecurityGroup
import io.github.yesidrobayo.shopledger.ui.settings.SecuritySettingsUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El modo discreto en el teléfono. Con el modo encendido, el árbol de Compose del detalle del
 * producto y de la compra nueva no contiene las cifras centinela, ni en texto ni en lo que lee
 * TalkBack; apagado sí, así que la prueba mide de verdad. El icono de la barra y la fila de Ajustes
 * piden el cambio, y la fila dice que oculta la pantalla y no protege archivos. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class DiscreetScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    /** Las cifras de todo el árbol, en textos y descripciones, sin separadores. */
    private fun digitsInTree(): String {
        val all = StringBuilder()
        fun visit(node: SemanticsNode) {
            val config = node.config
            config.getOrNull(SemanticsProperties.Text)?.forEach { all.append(it.text).append(' ') }
            config.getOrNull(SemanticsProperties.EditableText)?.let {
                all.append(it.text).append(' ')
            }
            config.getOrNull(SemanticsProperties.ContentDescription)?.forEach {
                all.append(it).append(' ')
            }
            node.children.forEach(::visit)
        }
        visit(compose.onNode(isRoot()).fetchSemanticsNode())
        return all.toString().split(' ').joinToString(" ") { word -> word.filter(Char::isDigit) }
    }

    private var discreet by mutableStateOf(false)

    /** Dibuja [content] una sola vez; el modo discreto cambia con [discreet]. */
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { PreviewTheme(discreet = discreet) { content() } }
        compose.waitForIdle()
    }

    private fun hide() {
        discreet = true
        compose.waitForIdle()
    }

    @Test
    fun theProductDetailHidesItsAverageCost() {
        val overview = ProductDetailSamples.overview()
        val state =
            ProductDetailUiState(
                ProductDetailContent.Ready(overview, ProductDetailSamples.pricing(overview)),
            )

        show { ProductDetailScreen(state, {}) }
        assertTrue(COST_DIGITS in digitsInTree())

        hide()
        assertFalse(COST_DIGITS in digitsInTree())
    }

    @Test
    fun theNewPurchaseHidesItsTotalButNotTheFieldBeingFilled() {
        show { NewPurchaseScreen(purchase(), {}) }
        assertTrue(PURCHASE_TOTAL_DIGITS in digitsInTree())

        hide()
        val digits = digitsInTree()
        assertFalse(PURCHASE_TOTAL_DIGITS in digits)
        // El costo que se está escribiendo sigue a la vista, para no guardar errores.
        assertTrue(FIELD_DIGITS in digits.split(' '))
    }

    @Test
    fun theBarIconAsksToHideAndThenToShow() {
        val asked = mutableListOf<Boolean>()
        compose.setContent {
            PreviewTheme(discreet = discreet) {
                CompositionLocalProvider(LocalDiscreetModeSwitch provides { asked += it }) {
                    DiscreetModeButton()
                }
            }
        }

        compose.onNodeWithContentDescription(text(R.string.a11y_discreet_hide)).performClick()
        discreet = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.a11y_discreet_show)).performClick()

        assertEquals(listOf(true, false), asked)
    }

    @Test
    fun theSettingSaysItHidesTheScreenAndDoesNotProtectFiles() {
        val asked = mutableListOf<Boolean>()
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalDiscreetModeSwitch provides { asked += it }) {
                    SecurityGroup(
                        SecuritySettingsUiState(AppLockSettings(false, AppLockTimeout.ONE_MINUTE)),
                        APP,
                    ) {}
                }
            }
        }

        compose.onNodeWithText(text(R.string.discreet_setting_help)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.discreet_setting)).performClick()

        assertEquals(listOf(true), asked)
    }

    private fun purchase(): NewPurchaseUiState {
        val unit = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
        return NewPurchaseUiState(
            loading = false,
            supplierName = "Distribuidora inventada",
            today = BusinessDate(LocalDate(2026, 11, 2)),
            weekStartDay = DayOfWeek.MONDAY,
            lines =
                listOf(
                    PurchaseLineUi(
                        key = 1L,
                        productId = 1L,
                        name = "Jabón de avena",
                        unit = unit,
                        quantityText = "24",
                        amountText = FIELD_DIGITS,
                        byTotal = false,
                        lineTotal = Money(13_579L),
                        derived = Money(417L),
                        costBefore = Money(380L),
                        costAfter = Money(401L),
                    ),
                ),
            total = Money(97_531L),
        )
    }

    private companion object {
        const val APP = "App de prueba"

        /** El costo promedio de la muestra, 3.800,00, en cifras. */
        const val COST_DIGITS = "380000"

        /** El total de la compra, 975,31, en cifras. */
        const val PURCHASE_TOTAL_DIGITS = "97531"

        /** El costo que se escribe en el campo. */
        const val FIELD_DIGITS = "417"
    }
}
