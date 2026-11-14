package io.github.yesidrobayo.shopledger.ui.quote

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentTotals
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las cotizaciones en el dispositivo: la lista con su etiqueta de vencida, el vacío que invita a
 * crear, el detalle con `Convertir en venta` solo mientras está abierta, y las hojas de vencida y
 * de precio cambiado. Con `captureDir`, las capturas de la puerta de calidad en claro y en oscuro.
 * Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class QuoteScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val listActions = mutableListOf<QuotesAction>()
    private val detailActions = mutableListOf<QuoteDetailAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theListMarksTheExpiredQuoteAndOpensOne() {
        show { QuotesScreen(LIST, { listActions += it }) }

        compose.onNodeWithText(text(R.string.quote_status_expired)).assertIsDisplayed()
        compose.onNodeWithText(PreviewSamples.CUSTOMER_NAME).performClick()
        assertTrue(QuotesAction.Open(41) in listActions)
        compose.onNodeWithText(text(R.string.quote_filter_converted)).performClick()
        assertTrue(QuotesAction.SelectFilter(QuoteStatus.CONVERTED) in listActions)
    }

    @Test
    fun withoutQuotesTheEmptyStateInvitesToCreateOne() {
        show { QuotesScreen(QuotesUiState(content = QuotesContent.Empty), { listActions += it }) }

        compose.onNodeWithText(text(R.string.quote_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.quote_empty_body)).assertIsDisplayed()
    }

    @Test
    fun anOpenQuoteOffersToConvertAndAConvertedOneToSeeItsSale() {
        show { QuoteDetailScreen(detailState(QuoteStatus.OPEN), { detailActions += it }) }

        compose.onNodeWithText(text(R.string.quote_no_effect_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.quote_convert_action)).performClick()
        assertTrue(QuoteDetailAction.Convert in detailActions)

        show { QuoteDetailScreen(detailState(QuoteStatus.CONVERTED), { detailActions += it }) }
        compose.onNodeWithText(text(R.string.quote_view_sale_action)).performClick()
        assertTrue(QuoteDetailAction.ViewSale in detailActions)
    }

    @Test
    fun aChangedPriceAsksWhichPriceToSellWith() {
        show {
            QuoteDetailScreen(
                detailState(QuoteStatus.OPEN).copy(dialog = QuoteDetailDialog.PriceChanged(2)),
                { detailActions += it },
            )
        }

        compose.onNodeWithText(text(R.string.quote_use_current_price_action)).performClick()
        assertTrue(QuoteDetailAction.ChoosePrices(useCurrentPrices = true) in detailActions)
    }

    @Test
    fun theMenuSharesAndCancels() {
        show { QuoteDetailScreen(detailState(QuoteStatus.OPEN), { detailActions += it }) }

        compose.onNodeWithContentDescription(text(R.string.a11y_more_options)).performClick()
        compose.onNodeWithText(text(R.string.quote_share_action)).performClick()
        assertTrue(QuoteDetailAction.Share in detailActions)
    }

    @Test
    fun drawsQuotesInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            show(dark) { QuotesScreen(LIST, {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-quotes-list.png"))
            show(dark) { QuotesScreen(QuotesUiState(content = QuotesContent.Empty), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-quotes-empty.png"))
            show(dark) { QuoteDetailScreen(detailState(QuoteStatus.OPEN), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-quote-detail.png"))
            show(dark) {
                QuoteDetailScreen(
                    detailState(QuoteStatus.OPEN).copy(dialog = QuoteDetailDialog.PriceChanged(2)),
                    {},
                )
            }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-quote-price-changed.png"))
        }
    }

    private fun detailState(status: QuoteStatus) = QuoteDetailUiState(
        content = QuoteDetailContent.Ready(
            QuoteDetail(
                id = 41,
                status = status,
                customerName = PreviewSamples.CUSTOMER_NAME,
                businessDate = TODAY,
                validUntil = VALID_UNTIL,
                expired = false,
                totals = DocumentTotals(Money(45_900), Money.ZERO, Money(45_900)),
                note = null,
                lines = listOf(
                    QuoteLineUi(
                        PreviewSamples.SHORT_NAME,
                        QuantityMilli(2_000),
                        null,
                        Money(4_200),
                        Money.ZERO,
                        Money(8_400),
                    ),
                    QuoteLineUi(
                        PreviewSamples.SECOND_NAME,
                        QuantityMilli(3_000),
                        null,
                        Money(12_500),
                        Money.ZERO,
                        Money(37_500),
                    ),
                ),
                convertedSaleId = if (status == QuoteStatus.CONVERTED) 7 else null,
            ),
        ),
    )

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(file: File) {
        val bitmap =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        val TODAY = BusinessDate(LocalDate(2026, 11, 5))
        val VALID_UNTIL = BusinessDate(LocalDate(2026, 11, 28))

        fun item(id: Long, name: String?, until: BusinessDate?, total: Money, expired: Boolean) =
            QuoteItem(id, name, until, total, QuoteStatus.OPEN, expired)

        val LIST = QuotesUiState(
            content = QuotesContent.Rows(
                listOf(
                    item(41, PreviewSamples.CUSTOMER_NAME, VALID_UNTIL, Money(45_900), false),
                    item(40, null, TODAY, Money(12_500), true),
                    item(39, PreviewSamples.LONG_NAME, null, PreviewSamples.LONG_AMOUNT, false),
                ),
            ),
        )
    }
}
