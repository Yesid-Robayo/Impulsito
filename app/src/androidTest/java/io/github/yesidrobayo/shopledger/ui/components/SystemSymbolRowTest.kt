package io.github.yesidrobayo.shopledger.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.TEST_CURRENCY
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.SymbolFont
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una etiqueta de moneda que solo tiene la fuente del sistema, como una en otro alfabeto, no
 * descuadra una fila de lista.
 *
 * En Android 15 o superior, esa corrida agranda la línea aunque el estilo fije su alto: medido en el
 * emulador API 37, un monto de fila pasa de 51 a 72 px y su línea base baja 5 px. `MoneyAmountText`
 * le devuelve la caja de sus cifras, y esta prueba lo comprueba en el componente dibujado.
 */
@RunWith(AndroidJUnit4::class)
class SystemSymbolRowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theLabelReallyNeedsTheSystemFont() {
        val parts = formatter(SYSTEM_LABEL).parts(AMOUNT)
        val symbol = CurrencySymbolFonts.chain(
            context.resources,
        ).resolve(parts.symbol, parts.currencyCode)

        assertTrue(symbol.runs.any { it.font == SymbolFont.SYSTEM })
    }

    @Test
    fun aSystemFontLabelKeepsTheRowHeightAndBaseline() {
        val styles = AppTheme.amounts.all.filter { it.first != "hero" && it.first != "large" }
        val measured = mutableMapOf<String, Pair<Int, Int>>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AppTheme(ColorPalette.INDIGO_MUSTARD, darkTheme = false) {
                        CompositionLocalProvider(
                            LocalAppFormats provides previewFormats(),
                            LocalDiscreetMode provides false,
                        ) {
                            Column {
                                for ((name, style) in styles) {
                                    for (symbol in listOf(SYSTEM_LABEL, OWN_SYMBOL)) {
                                        val display =
                                            rememberMoneyDisplay(AMOUNT, formatter(symbol))
                                        MoneyAmountText(
                                            display,
                                            style,
                                            Modifier.onGloballyPositioned {
                                                measured["$name $symbol"] =
                                                    it.size.height to it[FirstBaseline]
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        for ((name, _) in styles) {
            assertEquals(
                "Alto y línea base en $name",
                measured["$name $OWN_SYMBOL"],
                measured["$name $SYSTEM_LABEL"],
            )
        }
    }

    private fun formatter(symbol: String) = MoneyFormatter(
        formatLocale = requireNotNull(FormatLocale.of("en")),
        currencyCode = TEST_CURRENCY,
        currencyDecimals = 2,
        currencySymbol = symbol,
        uiLocale = Locale.ENGLISH,
    )

    private companion object {
        /** Una etiqueta árabe de moneda escrita a mano: ninguna fuente propia la tiene. */
        const val SYSTEM_LABEL = "د.إ"

        /** El signo genérico, que está en Figtree. */
        const val OWN_SYMBOL = "¤"

        val AMOUNT = Money(123_456L)
    }
}
