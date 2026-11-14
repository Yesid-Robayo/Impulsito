package io.github.yesidrobayo.shopledger.ui.money

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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyStatementLine
import io.github.yesidrobayo.shopledger.ui.clickWhenStill
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las cuentas de dinero en pantalla: la lista con el héroe, lo que falta por acreditar y las otras
 * monedas aparte; el vacío que invita a crear una cuenta; el libro por día; un movimiento de un
 * documento que lleva a él y uno manual que se anula. Con el modo discreto, toda cifra se oculta.
 * Con el argumento `captureDir` también deja las capturas de la puerta de calidad, en claro y
 * oscuro, con la paleta predeterminada y otra. Montos y nombres inventados.
 */
@RunWith(AndroidJUnit4::class)
class MoneyAccountsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(context.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<MoneyAccountsAction>()

    @Test
    fun theListShowsTheHeroTheAccountsAndOtherCurrenciesApartAndOpensAnAccount() {
        show(SAMPLE_LIST_STATE)

        compose.onNodeWithText(text(R.string.money_available)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.money_other_currencies)).assertExists()
        compose.onNodeWithText(text(R.string.money_pending_settlement, ""), substring = true)
            .assertExists()
        compose.onNodeWithText("Cuenta corriente").performClick()
        assertEquals(listOf<MoneyAccountsAction>(MoneyAccountsAction.Select(2)), actions)
    }

    @Test
    fun theFourActionsOpenTheirSheets() {
        show(SAMPLE_LIST_STATE)

        compose.onNodeWithText(text(R.string.money_draw_action)).performClick()
        compose.onNodeWithText(text(R.string.money_transfer_action)).performClick()
        assertEquals(
            listOf(
                MoneyAccountsAction.StartManual(ManualMoneyKind.OWNER_DRAW),
                MoneyAccountsAction.StartTransfer,
            ),
            actions,
        )
    }

    @Test
    fun withoutAccountsInUseItExplainsAndOffersToCreateOneKeepingTheActions() {
        show(SAMPLE_LIST_STATE.copy(content = MoneyAccountsContent.Ready(SAMPLE_EMPTY_VIEW)))

        compose.onNodeWithText(text(R.string.money_empty_title)).assertExists()
        compose.onNodeWithText(text(R.string.money_draw_action)).assertExists()
        compose.onNodeWithText(text(R.string.money_empty_action)).performClick()
        assertEquals(listOf<MoneyAccountsAction>(MoneyAccountsAction.NewAccount), actions)
    }

    @Test
    fun discreetModeHidesTheHeroEachBalanceAndWhatIsPending() {
        show(SAMPLE_LIST_STATE, discreet = true)

        val hidden = compose.onAllNodesWithContentDescription(
            text(R.string.a11y_discreet_hidden),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        // El héroe, los saldos a la vista y lo que falta por acreditar, cada uno en su nodo.
        assertTrue("Ocultos: ${hidden.size}", hidden.size >= 4)
    }

    @Test
    fun theStatementGoesByDayAndAMovementOpensFromItsRow() {
        show(SAMPLE_DETAIL_STATE)

        compose.onNodeWithText(text(R.string.money_balance)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.money_movement_owner_contribution)).performClick()
        assertEquals(listOf<MoneyAccountsAction>(MoneyAccountsAction.OpenMovement(6)), actions)
    }

    @Test
    fun anAccountWithoutMovementsSaysSo() {
        val ready = SAMPLE_DETAIL_STATE.detail as AccountDetailContent.Ready
        show(SAMPLE_DETAIL_STATE.copy(detail = ready.copy(days = emptyList())))

        compose.onNodeWithText(text(R.string.money_empty_movements)).assertIsDisplayed()
    }

    @Test
    fun aMovementFromADocumentSaysWhereItIsVoidedAndLeadsThere() {
        val receipt = line(2)
        show(SAMPLE_DETAIL_STATE.copy(movement = MovementSheet(receipt, MoneyDocument.Sale(7))))

        compose.onNodeWithText(text(R.string.money_automatic_notice)).assertExists()
        compose.clickWhenStill(compose.onNodeWithText(text(R.string.action_view)))
        assertEquals(
            listOf<MoneyAccountsAction>(MoneyAccountsAction.OpenDocument(MoneyDocument.Sale(7))),
            actions,
        )
    }

    @Test
    fun aManualMovementOffersItsVoid() {
        show(SAMPLE_DETAIL_STATE.copy(movement = MovementSheet(line(6))))

        compose.clickWhenStill(compose.onNodeWithText(text(R.string.money_void_action)))
        assertEquals(listOf<MoneyAccountsAction>(MoneyAccountsAction.AskVoid), actions)
    }

    @Test
    fun aNegativeBalanceIsWarnedInTheSheetWithoutBlocking() {
        show(
            SAMPLE_LIST_STATE.copy(
                manual = ManualSheet(
                    ManualMoneyKind.OWNER_DRAW,
                    SAMPLE_ACCOUNTS[0].id,
                    amountText = "200000",
                    negativeAfter = Money(-15_500),
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.money_negative_warning, ""), substring = true)
            .assertExists()
        compose.clickWhenStill(compose.onNodeWithText(text(R.string.money_save_action)))
        assertEquals(listOf<MoneyAccountsAction>(MoneyAccountsAction.Save), actions)
    }

    @Test
    fun capturesEveryStateInBothModesAndTwoPalettes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val ready = SAMPLE_DETAIL_STATE.detail as AccountDetailContent.Ready
        val states = listOf(
            "list" to SAMPLE_LIST_STATE,
            "empty" to SAMPLE_LIST_STATE.copy(
                content = MoneyAccountsContent.Ready(SAMPLE_EMPTY_VIEW),
            ),
            "archived" to SAMPLE_LIST_STATE.copy(showArchived = true),
            "error" to MoneyAccountsUiState(content = MoneyAccountsContent.ReadError),
            "detail" to SAMPLE_DETAIL_STATE,
            "detail-empty" to SAMPLE_DETAIL_STATE.copy(detail = ready.copy(days = emptyList())),
            "draw" to SAMPLE_LIST_STATE.copy(
                manual = ManualSheet(
                    ManualMoneyKind.OWNER_DRAW,
                    SAMPLE_ACCOUNTS[0].id,
                    amountText = "200000",
                    note = "Mercado",
                    negativeAfter = Money(-15_500),
                ),
            ),
            "other-income-error" to SAMPLE_LIST_STATE.copy(
                manual = ManualSheet(
                    ManualMoneyKind.OTHER_INCOME,
                    SAMPLE_ACCOUNTS[1].id,
                    errors = setOf(MoneyFormError.AMOUNT_ZERO),
                    notice = MoneyFormNotice.SaveFailed(outOfSpace = false),
                ),
            ),
            "transfer-fx" to SAMPLE_LIST_STATE.copy(
                transfer = TransferSheet(
                    fromId = SAMPLE_ACCOUNTS[1].id,
                    toId = 4,
                    amountText = "10000",
                    rateText = "0,25",
                    arrives = Money(250_000),
                    errors = emptySet(),
                ),
            ),
            "transfer-same" to SAMPLE_LIST_STATE.copy(
                transfer = TransferSheet(
                    fromId = SAMPLE_ACCOUNTS[0].id,
                    toId = SAMPLE_ACCOUNTS[0].id,
                    amountText = "5000",
                    errors = setOf(MoneyFormError.SAME_ACCOUNT),
                ),
            ),
            "new-account" to SAMPLE_LIST_STATE.copy(
                editor = AccountEditor(
                    editingId = null,
                    name = "Billetera del celular",
                    kind = MoneyAccountKind.WALLET,
                    currency = SAMPLE_BOOK,
                    currencyName = "Moneda de prueba",
                    openingText = "-2500",
                    errors = setOf(MoneyFormError.NAME_TAKEN),
                ),
            ),
            "movement-document" to
                SAMPLE_DETAIL_STATE.copy(movement = MovementSheet(line(2), MoneyDocument.Sale(7))),
            "movement-manual" to SAMPLE_DETAIL_STATE.copy(movement = MovementSheet(line(6))),
            "void" to SAMPLE_DETAIL_STATE.copy(dialog = MoneyDialog.Void(6, isTransfer = false)),
            "archive" to
                SAMPLE_DETAIL_STATE.copy(dialog = MoneyDialog.Archive(SAMPLE_ACCOUNTS[1])),
            "loading" to MoneyAccountsUiState(),
        )
        for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
            for (dark in listOf(false, true)) {
                for ((name, state) in states) {
                    show(state, dark = dark, palette = palette)
                    val mode = if (dark) "dark" else "light"
                    shoot(requireNotNull(dir), "${palette.name.lowercase()}-$mode-$name")
                }
            }
        }
        show(SAMPLE_LIST_STATE, discreet = true)
        shoot(requireNotNull(dir), "list-discreet")
        show(SAMPLE_DETAIL_STATE, discreet = true)
        shoot(requireNotNull(dir), "detail-discreet")
    }

    private fun line(id: Long): MoneyStatementLine {
        val ready = SAMPLE_DETAIL_STATE.detail as AccountDetailContent.Ready
        return ready.days.flatMap { it.lines }.first { it.movement.id == id }
    }

    private fun show(
        state: MoneyAccountsUiState,
        dark: Boolean = false,
        discreet: Boolean = false,
        palette: ColorPalette = ColorPalette.INDIGO_MUSTARD,
    ) {
        actions.clear()
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = palette, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { MoneyAccountsContentHost(state) { actions += it } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(dir: File, name: String) {
        compose.waitForIdle()
        // El teclado que abre el campo con foco taparía la hoja: la captura muestra la hoja entera.
        compose.runOnUiThread {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "money-$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}
