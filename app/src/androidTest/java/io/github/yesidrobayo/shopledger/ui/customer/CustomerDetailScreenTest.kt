package io.github.yesidrobayo.shopledger.ui.customer

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentPreview
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El detalle de un cliente en pantalla: el saldo con palabras y su vencimiento, el extracto con el
 * saldo de cada fila y el abono anulado marcado, los segmentos vacíos y las acciones que llegan a
 * quien las maneja. Con el modo discreto, el saldo de quien se atiende sigue a la vista. Con el
 * argumento `captureDir` también deja las capturas de la puerta de calidad, en claro y oscuro.
 * Nombres y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class CustomerDetailScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(context.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<CustomerDetailAction>()

    @Test
    fun theBalanceSaysWhoOwesAndWhenItIsDue() {
        show(READY)

        compose.onNodeWithText(NAME).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.customer_balance)).assertIsDisplayed()
        // La insignia dice solo la palabra: el monto ya está al lado, en grande.
        compose.onNodeWithText(text(R.string.customer_balance_owes_short)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.customer_due_on, DUE_TEXT), substring = true)
            .assertExists()
    }

    @Test
    fun theStatementShowsEachRowAndOnlyALivePaymentOffersToBeVoided() {
        show(READY)
        scrollTo(text(R.string.customer_ledger_credit_sale))
        compose.onNodeWithText(text(R.string.customer_ledger_credit_sale)).assertExists()

        // Solo los dos abonos: el vigente y el anulado, los dos a la vista.
        show(READY.withView { it.copy(lines = listOf(LINES[0], LINES[2])) })
        val payments = compose.onAllNodesWithText(text(R.string.customer_ledger_payment))
        payments[0].performClick()
        payments[1].performClick()

        assertEquals(listOf(CustomerDetailAction.AskVoidPayment(4)), actions)
    }

    @Test
    fun theFixedActionRecordsAPaymentEvenWithZeroBalance() {
        show(
            READY.withView {
                it.copy(
                    customer = it.customer.copy(balance = Money.ZERO),
                    lines = emptyList(),
                    due = DueStatus.Clear,
                )
            },
        )

        compose.onNodeWithText(text(R.string.customer_balance_clear)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.customer_empty_movements)).assertExists()
        compose.onNodeWithText(text(R.string.customer_payment_action)).performClick()

        assertEquals(listOf(CustomerDetailAction.OpenPayment), actions)
    }

    @Test
    fun theSalesSegmentAsksForItsRowsAndSaysWhenThereAreNone() {
        show(READY)
        compose.onNodeWithText(text(R.string.customer_tab_sales)).performClick()
        assertEquals(listOf(CustomerDetailAction.SelectTab(CustomerDetailTab.SALES)), actions)

        show(
            READY.copy(
                tab = CustomerDetailTab.SALES,
                sales = CustomerSales(complete = true, started = true),
            ),
        )
        compose.onNodeWithText(text(R.string.customer_empty_sales)).assertExists()
    }

    @Test
    fun overdueSaysSoWithWordsAndDiscreetModeKeepsThisBalanceInSight() {
        show(READY.withView { it.copy(due = DueStatus.Overdue(DUE_DATE)) }, discreet = true)

        compose.onNodeWithText(text(R.string.customer_overdue_since, DUE_TEXT), substring = true)
            .assertExists()
        compose.onNodeWithContentDescription(
            text(R.string.a11y_discreet_hidden),
        ).assertDoesNotExist()
    }

    @Test
    fun theReminderOnlyLeavesThroughAnotherAppByATap() {
        show(READY.copy(reminder = ReminderSheet(template = "Hola {customer}", details = "Cuenta")))

        compose.onNodeWithText(text(R.string.customer_reminder_note, text(R.string.app_name)))
            .assertExists()
        tapWhenShown(text(R.string.customer_reminder_attach_details))
        tapWhenShown(text(R.string.customer_reminder_send_action))
        tapWhenShown(text(R.string.action_share))

        assertEquals(
            listOf(
                CustomerDetailAction.ReminderDetails(true),
                CustomerDetailAction.SendReminder(sms = true),
                CustomerDetailAction.SendReminder(sms = false),
            ),
            actions,
        )
    }

    @Test
    fun capturesEveryStateInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states = listOf(
            "ready" to READY,
            "overdue" to READY.withView { it.copy(due = DueStatus.Overdue(DUE_DATE)) },
            "credit" to READY.withView {
                it.copy(
                    customer = it.customer.copy(balance = Money(-2_500)),
                    due = DueStatus.Credit,
                )
            },
            "empty" to READY.withView {
                it.copy(
                    customer = it.customer.copy(balance = Money.ZERO),
                    lines = emptyList(),
                    due = DueStatus.Clear,
                )
            },
            "sales" to READY.copy(
                tab = CustomerDetailTab.SALES,
                sales = CustomerSales(rows = SALES, complete = true, started = true),
            ),
            "payment" to READY.copy(
                payment = PaymentSheet(
                    amountText = "4000",
                    preview = PaymentPreview.RemainingDebt(Money(3_000)),
                ),
            ),
            "reminder" to READY.copy(
                reminder = ReminderSheet(
                    template = "Hola, {customer}. El saldo pendiente es {amount}. Gracias.",
                    details = "Cuenta de prueba 000-000",
                ),
            ),
            "loading" to CustomerDetailUiState(),
            "read-failed" to CustomerDetailUiState(content = CustomerDetailContent.ReadError),
        )
        for (dark in listOf(false, true)) {
            for ((name, state) in states) {
                show(state, dark = dark)
                shoot(requireNotNull(dir), "$name-${if (dark) "dark" else "light"}")
            }
        }
    }

    /**
     * Toca [value] cuando ya se ve. La hoja del recordatorio entra con una animación: con el
     * equipo cargado, un toque hecho antes de que termine cae fuera del botón y se pierde.
     */
    private fun tapWhenShown(value: String) {
        compose.waitUntil(SHEET_TIMEOUT_MS) { compose.onNodeWithText(value).isDisplayed() }
        compose.onNodeWithText(value).performClick()
    }

    private fun show(
        state: CustomerDetailUiState,
        dark: Boolean = false,
        discreet: Boolean = false,
    ) {
        actions.clear()
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) {
                        CustomerDetailScreen(state, { actions += it })
                        state.payment?.let {
                            PaymentSheetHost(state, it) { action -> actions += action }
                        }
                        state.reminder?.let {
                            ReminderSheetHost(it, hasPhone = true) { action -> actions += action }
                        }
                    }
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

    private fun scrollTo(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
    }

    private companion object {
        const val SHEET_TIMEOUT_MS = 5_000L
        const val SETTLE_MILLIS = 700L
        const val NAME = "Cliente de prueba"
        val AT = Instant.parse("2026-11-01T12:00:00Z")
        val DATE = requireNotNull(BusinessDate.parse("2026-11-01"))
        val DUE_DATE = requireNotNull(BusinessDate.parse("2026-11-20"))
        val DUE_TEXT: String = previewFormats().date.dayMonthYear(DUE_DATE)

        val CUSTOMER = Customer(
            id = 5,
            contact = CustomerContact(NAME, "cliente de prueba", "300 000 0000", null, null),
            priceListId = null,
            balance = Money(7_000),
            pointsBalance = 0,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )

        fun entry(
            id: Long,
            type: CustomerLedgerEntryType,
            amount: Long,
            after: Long,
            source: CustomerEntrySource = CustomerEntrySource.None,
        ) = CustomerLedgerEntry(
            id, 5, type,
            Money(
                amount,
            ),
            Money(after), source, null, null, AT, DATE,
        )

        val LINES = listOf(
            StatementLine(
                entry(4, CustomerLedgerEntryType.PAYMENT, -3_000, 7_000),
                voided = false,
                voidable = true,
            ),
            StatementLine(
                entry(
                    3,
                    CustomerLedgerEntryType.ENTRY_VOID,
                    6_000,
                    10_000,
                    CustomerEntrySource.Reverses(2),
                ),
                voided = false,
                voidable = false,
            ),
            StatementLine(
                entry(2, CustomerLedgerEntryType.PAYMENT, -6_000, 4_000),
                voided = true,
                voidable = false,
            ),
            StatementLine(
                entry(
                    1,
                    CustomerLedgerEntryType.CREDIT_SALE,
                    10_000,
                    10_000,
                    CustomerEntrySource.Sale(1_180),
                ),
                voided = false,
                voidable = false,
            ),
        )

        val READY = CustomerDetailUiState(
            content = CustomerDetailContent.Ready(
                CustomerDetailView(
                    CUSTOMER,
                    LINES,
                    DueStatus.DueOn(DUE_DATE),
                    loyaltyEnabled = false,
                ),
            ),
            today = DATE,
            weekStartDay = kotlinx.datetime.DayOfWeek.MONDAY,
        )

        val SALES = List(3) { index ->
            SaleHistoryRow(
                id = index + 1L,
                occurredAt = AT,
                businessDate = DATE,
                total = Money(3_500L * (index + 1)),
                voided = index == 1,
                firstProductName = "Producto de prueba",
                lineCount = index + 1,
                methodName = null,
                paymentCount = 1,
            )
        }

        fun CustomerDetailUiState.withView(change: (CustomerDetailView) -> CustomerDetailView) =
            copy(
                content = CustomerDetailContent.Ready(
                    change((content as CustomerDetailContent.Ready).view),
                ),
            )
    }
}
