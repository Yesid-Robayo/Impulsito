package io.github.yesidrobayo.shopledger.ui.currency

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.TEST_CURRENCY
import io.github.yesidrobayo.shopledger.ui.components.MoneyAmountText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que TalkBack recibe de un monto en pantalla: el nodo de accesibilidad que arma Compose, leído
 * como lo lee un servicio de accesibilidad. Las cifras y el nombre de la moneda en el idioma de la
 * app, nunca el símbolo ni el idioma del teléfono.
 *
 * No usa herramientas de interfaz: abre una actividad vacía, dibuja los montos y pide el árbol de
 * accesibilidad de la ventana. Con el argumento `captureDir`, guarda además una captura con los 57
 * símbolos, para revisarla a ojo.
 */
@RunWith(AndroidJUnit4::class)
class MoneyTalkBackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources

    @Test
    fun talkBackReadsTheDigitsAndTheNameOfTheCurrencyInTheLanguageOfTheApp() {
        val usd = requireNotNull(CurrencyCode.of("USD"))
        val spanish = formatter("es-ES", usd, uiLocale = Locale.forLanguageTag("es"))
        val english = formatter("es-ES", usd, uiLocale = Locale.ENGLISH)
        val previous = Locale.getDefault()
        // El teléfono en otro idioma: el nombre no debe salir en ese.
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            show {
                Column {
                    MoneyAmountText(
                        rememberMoneyDisplay(Money(-120_000), spanish),
                        AppTheme.amounts.row,
                    )
                    MoneyAmountText(rememberMoneyDisplay(Money(100), english), AppTheme.amounts.row)
                }
            }
            val read = descriptions()
            val negative = spanish.parts(Money(-120_000))
            assertEquals(
                listOf(
                    resources.getString(
                        R.string.a11y_minus_amount,
                        resources.getString(
                            R.string.a11y_money_amount,
                            negative.number,
                            negative.currencyName,
                        ),
                    ),
                    resources.getString(
                        R.string.a11y_money_amount,
                        "1,00",
                        english.parts(Money(100)).currencyName,
                    ),
                ),
                read,
            )
            assertTrue(read[0], read[0].contains("dólares estadounidenses"))
            assertTrue(read[1], read[1].contains("US dollars"))
            // TalkBack nunca recibe el símbolo: lee el nombre.
            assertFalse(read.joinToString(), read.any { it.contains("US$") || it.contains("$") })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun anUnknownCurrencyIsReadWithItsIsoCode() {
        val unknown = requireNotNull(CurrencyCode.of("QQQ"))
        val parts = formatter("en", unknown, Locale.ENGLISH).parts(Money(500))
        assertEquals("QQQ", parts.currencyName)
        assertEquals("5.00 QQQ", moneyDescription(parts, resources))
    }

    @Test
    fun captureTheSymbolSheet() {
        val dir =
            InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
                File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
            } ?: return
        for ((index, page) in SHEET.chunked(SHEET.size / 2 + 1).withIndex()) {
            for (dark in listOf(false, true)) {
                show(dark) {
                    FlowRow(
                        modifier = Modifier.statusBarsPadding().padding(AppTheme.spacing.s16),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
                    ) {
                        for ((tag, symbol) in page) {
                            MoneyAmountText(
                                rememberMoneyDisplay(
                                    Money(-123_456),
                                    formatter(tag, TEST_CURRENCY, Locale.ENGLISH, symbol),
                                ),
                                AppTheme.amounts.row,
                            )
                        }
                    }
                }
                val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(
                    dir,
                    "symbols-${index + 1}-${if (dark) "dark" else "light"}.png",
                ).outputStream().use {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After
    fun closeTheActivity() {
        scenario?.close()
    }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        scenario?.close()
        scenario =
            ActivityScenario.launch(ComponentActivity::class.java).also {
                it.onActivity { activity ->
                    activity.setContent {
                        AppTheme(palette = DefaultColorPalette, darkTheme = dark) {
                            Column(
                                Modifier.fillMaxSize().background(
                                    MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(SETTLE_MS)
    }

    /** Las descripciones de la ventana, en orden de lectura, como las recibe un lector de pantalla. */
    private fun descriptions(): List<String> {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (true) {
            val found = mutableListOf<String>()
            instrumentation.uiAutomation.rootInActiveWindow?.let { collect(it, found) }
            if (found.isNotEmpty() || SystemClock.uptimeMillis() > deadline) return found
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun collect(node: AccessibilityNodeInfo, into: MutableList<String>) {
        node.contentDescription?.let { into += it.toString() }
        for (child in 0 until node.childCount) node.getChild(child)?.let { collect(it, into) }
    }

    private fun formatter(
        tag: String,
        code: CurrencyCode,
        uiLocale: Locale,
        symbol: String? = null,
    ): MoneyFormatter =
        MoneyFormatter(requireNotNull(FormatLocale.of(tag)), code, 2, symbol, uiLocale)

    private companion object {
        const val SETTLE_MS = 500L
        const val POLL_MS = 100L
        const val TIMEOUT_MS = 5_000L

        /** Los 57 signos y tres símbolos inventados, con el símbolo antes y después de las cifras. */
        val SHEET: List<Pair<String, String>> =
            (
                (
                    listOf(0x0024, 0x00A2, 0x00A3, 0x00A4, 0x00A5, 0x058F, 0x060B, 0x07FE, 0x07FF) +
                        listOf(0x09F2, 0x09F3, 0x09FB, 0x0AF1, 0x0BF9, 0x0E3F, 0x17DB) +
                        (0x20A0..0x20C0).toList() +
                        listOf(0xA838, 0xFDFC, 0xFE69, 0xFF04, 0xFFE0, 0xFFE1, 0xFFE5, 0xFFE6)
                    ).map { String(Character.toChars(it)) } + listOf("XY\u20B5", "Дж", "بج")
                ).withIndex().map { (index, symbol) ->
                (if (index % 2 == 0) "en" else "fr") to
                    symbol
            }
    }
}
