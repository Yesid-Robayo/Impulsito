package io.github.yesidrobayo.shopledger.ui.cash

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashClosing
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashSessionFigures
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La caja en pantalla: opcional cuando nunca se usó, abierta con sus cifras y el aviso de otro día,
 * los cierres con la diferencia dicha con palabras, el cierre con lo contado y las denominaciones
 * vacías. Con el modo discreto, las cifras de la caja se ocultan. Con el argumento `captureDir`
 * también deja las capturas de la puerta de calidad, en claro y oscuro. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class CashRegisterScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(context.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<CashRegisterAction>()

    @Test
    fun neverUsedSaysItIsOptionalAndOffersToOpen() {
        show(EMPTY)

        compose.onNodeWithText(text(R.string.cash_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cash_empty_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cash_open_action)).performClick()
        assertEquals(listOf<CashRegisterAction>(CashRegisterAction.StartOpen), actions)
    }

    @Test
    fun anOpenRegisterFromAnotherDaySaysSoAndOffersToClose() {
        show(OPEN_YESTERDAY)

        compose.onNodeWithText(text(R.string.cash_open_old_day, YESTERDAY_TEXT)).assertExists()
        compose.onNodeWithText(text(R.string.cash_expected), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.cash_close_action)).performClick()
        assertEquals(listOf<CashRegisterAction>(CashRegisterAction.StartClose), actions)
    }

    @Test
    fun aPastClosingSaysItsDifferenceWithWordsAndOpensItsDetail() {
        show(OPEN_TODAY)

        val short = text(R.string.cash_short, "").trim()
        compose.onNodeWithText(short, substring = true).assertExists()
        compose.onNodeWithText(text(R.string.cash_no_difference), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.cash_no_difference), substring = true).performClick()
        assertEquals(listOf<CashRegisterAction>(CashRegisterAction.OpenClosed(4)), actions)
    }

    @Test
    fun discreetModeHidesTheFiguresOfTheRegister() {
        show(OPEN_TODAY, discreet = true)

        // La tarjeta se lee de una vez: cada cifra oculta se cuenta en el árbol sin fusionar.
        val hidden =
            compose.onAllNodesWithContentDescription(
                text(R.string.a11y_discreet_hidden),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
        // Las cinco cifras de la tarjeta, y lo contado y la diferencia de cada cierre.
        assertTrue(hidden.size >= 5)
    }

    @Test
    fun closingShowsTheDifferenceAndCountingWithoutDenominationsLeadsToWriteThem() {
        show(CLOSING)
        compose.onNodeWithText(text(R.string.cash_difference)).assertExists()
        compose.onNodeWithText(text(R.string.cash_count_by_denomination)).performClick()
        assertEquals(listOf<CashRegisterAction>(CashRegisterAction.OpenDenominations), actions)

        show(OPEN_TODAY.copy(showDenominations = true))
        compose.onNodeWithText(text(R.string.cash_denominations_empty_title)).assertIsDisplayed()
    }

    @Test
    fun capturesEveryStateInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states = listOf(
            "empty" to EMPTY,
            "open" to OPEN_TODAY,
            "open-yesterday" to OPEN_YESTERDAY,
            "open-sheet" to EMPTY.copy(
                openSheet = OpenCashSheet(ACCOUNT, CashCountInput(amountText = "12000")),
            ),
            "closing" to CLOSING,
            "closing-denominations" to CLOSING.copy(
                denominations = DENOMINATIONS,
                closeForm = CLOSING.closeForm?.copy(
                    count = CashCountInput(
                        byDenomination = true,
                        counts = mapOf(1L to "2", 2L to "3", 3L to "4", 4L to "1"),
                        counted = Money(39_500),
                    ),
                ),
            ),
            "denominations" to
                OPEN_TODAY.copy(showDenominations = true, denominations = DENOMINATIONS),
            "denominations-empty" to OPEN_TODAY.copy(showDenominations = true),
            "loading" to CashRegisterUiState(),
        )
        for (dark in listOf(false, true)) {
            for ((name, state) in states) {
                show(state, dark = dark)
                shoot(requireNotNull(dir), "$name-${if (dark) "dark" else "light"}")
            }
        }
        show(OPEN_TODAY, discreet = true)
        shoot(requireNotNull(dir), "open-discreet")
    }

    private fun show(state: CashRegisterUiState, dark: Boolean = false, discreet: Boolean = false) {
        actions.clear()
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { CashRegisterContentHost(state) { actions += it } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(dir: File, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private companion object {
        const val SETTLE_MILLIS = 700L
        const val ACCOUNT = 1L
        val AT = Instant.parse("2026-11-13T13:02:00Z")
        val TODAY = requireNotNull(BusinessDate.parse("2026-11-13"))
        val YESTERDAY = requireNotNull(BusinessDate.parse("2026-11-12"))
        val YESTERDAY_TEXT: String = previewFormats().date.dayMonthYear(YESTERDAY)

        val ACCOUNT_ROW = MoneyAccount(
            id = ACCOUNT,
            systemKey = "cash",
            details = MoneyAccountDetails(null, null, MoneyAccountKind.CASH),
            currency = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 0),
            balance = Money(12_000),
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )

        fun session(id: Long, date: BusinessDate, closing: CashClosing? = null) =
            CashSession(id, NewCashSession(ACCOUNT, Money(50_000), AT, date), closing)

        val FIGURES = CashSessionFigures(
            opening = Money(50_000),
            sales = Money(212_000),
            cashIn = Money(10_000),
            cashOut = Money(-30_000),
            expected = Money(242_000),
        )

        val CLOSED = listOf(
            session(
                4,
                YESTERDAY,
                CashClosing(Money(198_000), Money(198_000), Money.ZERO, AT, null),
            ),
            session(
                3,
                YESTERDAY.plusDays(-1),
                CashClosing(Money(172_000), Money(171_500), Money(-500), AT, null),
            ),
        )

        fun ready(open: OpenCash?, closed: List<CashSession>) = CashRegisterUiState(
            content = CashRegisterContent.Ready(CashRegisterView(ACCOUNT_ROW, open, closed)),
            accounts = listOf(ACCOUNT_ROW),
            today = TODAY,
            zone = BusinessTimeZone.of("Etc/UTC"),
        )

        val EMPTY = ready(null, emptyList())
        val OPEN_TODAY = ready(OpenCash(session(5, TODAY), FIGURES, fromAnotherDay = false), CLOSED)
        val OPEN_YESTERDAY =
            ready(OpenCash(session(5, YESTERDAY), FIGURES, fromAnotherDay = true), CLOSED)
        val CLOSING = OPEN_TODAY.copy(
            closeForm = CloseCashForm(
                sessionId = 5,
                expected = Money(242_000),
                count = CashCountInput(amountText = "241500", counted = Money(241_500)),
            ),
        )

        val DENOMINATIONS = listOf(10_000L, 5_000L, 1_000L, 500L).mapIndexed { index, value ->
            CashDenomination(index + 1L, Money(value), index != 3, index, AT, AT)
        }
    }
}
