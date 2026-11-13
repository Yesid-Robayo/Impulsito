package io.github.yesidrobayo.shopledger.ui.payable

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableRow
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableStatus
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayablesOverview
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
 * Las cuentas por pagar en pantalla: la lista con lo vencido dicho con palabras, el saldo a favor
 * en su grupo, las cifras ocultas con el modo discreto y los estados vacíos; y el libro de un
 * proveedor con su vencimiento, el pago que se ofrece anular y la hoja de pago. Con el argumento
 * `captureDir` también deja las capturas de la puerta de calidad, en claro y oscuro. Nombres y
 * montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class PayablesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(context.cacheDir, name).apply { mkdirs() }
        }
    private val listActions = mutableListOf<PayablesAction>()
    private val detailActions = mutableListOf<PayableDetailAction>()

    @Test
    fun overdueSaysSoWithWordsAndTheCreditGoesInItsOwnGroup() {
        showList(LIST)

        compose.onNodeWithText(text(R.string.payable_total)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.payable_due_7_days)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.payable_overdue_since, OVERDUE_TEXT)).assertExists()
        // En inglés, la etiqueta y el filtro dicen lo mismo: basta con que la etiqueta esté.
        assertTrue(
            compose.onAllNodesWithText(text(R.string.payable_overdue_tag), substring = true)
                .fetchSemanticsNodes().size >= 2,
        )
        compose.onNodeWithText(text(R.string.payable_next_due, DUE_TEXT)).assertExists()
        // `Saldo a favor` es a la vez el filtro y el encabezado de su grupo.
        assertEquals(
            2,
            compose.onAllNodesWithText(
                text(R.string.payable_filter_credit),
            ).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(text(R.string.payable_archived_tag)).assertExists()

        compose.onNodeWithText(NAME_OVERDUE).performClick()
        assertEquals(listOf<PayablesAction>(PayablesAction.OpenSupplier(1)), listActions)
    }

    @Test
    fun discreetModeHidesEveryFigureOfTheList() {
        showList(LIST, discreet = true)

        val hidden = compose.onAllNodesWithContentDescription(text(R.string.a11y_discreet_hidden))
            .fetchSemanticsNodes()
        // Los dos totales y las tres filas.
        assertTrue(hidden.size >= 5)
    }

    @Test
    fun emptyInvitesToBuyAndAFilterWithoutRowsOffersToClearIt() {
        showList(PayablesUiState(content = PayablesContent.Empty))
        compose.onNodeWithText(text(R.string.payable_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.payable_empty_action)).performClick()
        assertEquals(listOf<PayablesAction>(PayablesAction.NewPurchase), listActions)

        showList(
            PayablesUiState(
                content = PayablesContent.Ready(OVERVIEW, emptyList()),
                filter = PayableFilter.OVERDUE,
            ),
        )
        compose.onNodeWithText(text(R.string.payable_filter_empty)).assertExists()
        compose.onNodeWithText(text(R.string.action_clear_filters)).performClick()
        assertEquals(listOf<PayablesAction>(PayablesAction.ClearFilters), listActions)
    }

    @Test
    fun theLedgerSaysWhenItIsDueAndOnlyALivePaymentOffersToBeVoided() {
        showDetail(DETAIL)

        compose.onNodeWithText(NAME_OVERDUE).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.purchase_due_status, DUE_TEXT)).assertExists()
        val payments = compose.onAllNodesWithText(text(R.string.payable_ledger_payment))
        payments[0].performClick()
        payments[1].performClick()
        assertEquals(
            listOf<PayableDetailAction>(PayableDetailAction.AskVoidPayment(4)),
            detailActions,
        )

        detailActions.clear()
        compose.onNodeWithText(text(R.string.payable_payment_action)).performClick()
        assertEquals(listOf<PayableDetailAction>(PayableDetailAction.OpenPayment), detailActions)
    }

    @Test
    fun theSheetSaysWhatThePaymentLeavesOrTheCreditItCreates() {
        showDetail(
            DETAIL.copy(
                payment = SupplierPaymentSheet(
                    amountText = "8000",
                    preview = PayablePreview.CreditLeft(Money(2_000)),
                ),
            ),
        )
        // La frase sin el monto, que va con el formato regional de la vista previa.
        val phrase = text(R.string.payable_credit_left, "").trim()
        compose.onNodeWithText(phrase, substring = true).assertExists()
    }

    @Test
    fun capturesEveryStateInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val lists = listOf(
            "list" to LIST,
            "list-empty" to PayablesUiState(content = PayablesContent.Empty),
            "list-loading" to PayablesUiState(),
        )
        val details = listOf(
            "detail" to DETAIL,
            "detail-overdue" to DETAIL.withView { it.copy(due = PayableDue.Overdue(OVERDUE)) },
            "detail-credit" to DETAIL.withView {
                it.copy(
                    supplier = it.supplier.copy(balance = Money(-4_000)),
                    due = PayableDue.Credit,
                    voidedPurchaseLeftCredit = true,
                )
            },
            "detail-payment" to DETAIL.copy(
                payment = SupplierPaymentSheet(
                    amountText = "4000",
                    preview = PayablePreview.Remaining(Money(2_000)),
                ),
            ),
        )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for ((name, state) in lists) {
                showList(state, dark = dark)
                shoot(requireNotNull(dir), "$name-$mode")
            }
            for ((name, state) in details) {
                showDetail(state, dark = dark)
                shoot(requireNotNull(dir), "$name-$mode")
            }
        }
        showList(LIST, discreet = true)
        shoot(requireNotNull(dir), "list-discreet")
    }

    private fun showList(state: PayablesUiState, dark: Boolean = false, discreet: Boolean = false) {
        listActions.clear()
        show(dark, discreet) { PayablesScreen(state, { listActions += it }) }
    }

    private fun showDetail(state: PayableDetailUiState, dark: Boolean = false) {
        detailActions.clear()
        show(dark, discreet = false) {
            PayableDetailScreen(state, { detailActions += it })
            state.payment?.let {
                SupplierPaymentSheetHost(state, it) { action ->
                    detailActions +=
                        action
                }
            }
        }
    }

    private fun show(dark: Boolean, discreet: Boolean, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { content() }
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
        const val NAME_OVERDUE = "Proveedor de prueba con un nombre largo"
        val AT = Instant.parse("2026-11-01T12:00:00Z")
        val TODAY = requireNotNull(BusinessDate.parse("2026-11-10"))
        val OVERDUE = requireNotNull(BusinessDate.parse("2026-11-05"))
        val DUE = requireNotNull(BusinessDate.parse("2026-11-20"))
        val OVERDUE_TEXT: String = previewFormats().date.dayMonthYear(OVERDUE)
        val DUE_TEXT: String = previewFormats().date.dayMonthYear(DUE)

        fun supplier(id: Long, name: String, balance: Long, archived: Boolean = false) = Supplier(
            id = id,
            details = SupplierDetails(name, name.lowercase(), null, null, null, 30),
            balance = Money(balance),
            archivedAt = AT.takeIf { archived },
            createdAt = AT,
            updatedAt = AT,
        )

        val OVERVIEW = PayablesOverview(
            today = TODAY,
            rows = listOf(
                PayableRow(
                    supplier(1, NAME_OVERDUE, 6_000),
                    PayableStatus.OVERDUE,
                    Money(6_000),
                    Money.ZERO,
                    OVERDUE,
                    Money(6_000),
                    null,
                ),
                PayableRow(
                    supplier(2, "Proveedor próximo", 125_000, archived = true),
                    PayableStatus.UPCOMING,
                    Money(125_000),
                    Money.ZERO,
                    null,
                    Money.ZERO,
                    DUE,
                ),
                PayableRow(
                    supplier(3, "Proveedor con saldo a favor", -2_500),
                    PayableStatus.IN_FAVOR,
                    Money.ZERO,
                    Money(2_500),
                    null,
                    Money.ZERO,
                    null,
                ),
            ),
            owed = Money(131_000),
            dueWithinWeek = Money.ZERO,
            overdueCount = 1,
        )

        val LIST = PayablesUiState(content = PayablesContent.Ready(OVERVIEW, OVERVIEW.rows))

        fun entry(
            id: Long,
            type: SupplierLedgerEntryType,
            amount: Long,
            after: Long,
            purchase: Long? = null,
            reverses: Long? = null,
            due: BusinessDate? = null,
        ) = SupplierLedgerEntry(
            id,
            NewSupplierLedgerEntry(
                supplierId = 1,
                type = type,
                amount = Money(amount),
                balanceAfter = Money(after),
                purchaseId = purchase,
                reversesEntryId = reverses,
                dueDate = due,
                occurredAt = AT,
                businessDate = TODAY,
                createdAt = AT,
            ),
        )

        val DETAIL = PayableDetailUiState(
            content = PayableDetailContent.Ready(
                PayableDetailView(
                    supplier = supplier(1, NAME_OVERDUE, 6_000),
                    lines = listOf(
                        PayableLine(
                            entry(4, SupplierLedgerEntryType.PAYMENT, -4_000, 6_000),
                            voided = false,
                            voidable = true,
                        ),
                        PayableLine(
                            entry(
                                3,
                                SupplierLedgerEntryType.ENTRY_VOID,
                                6_000,
                                10_000,
                                reverses = 2,
                            ),
                            voided = false,
                            voidable = false,
                        ),
                        PayableLine(
                            entry(2, SupplierLedgerEntryType.PAYMENT, -6_000, 4_000),
                            voided = true,
                            voidable = false,
                        ),
                        PayableLine(
                            entry(
                                1,
                                SupplierLedgerEntryType.CREDIT_PURCHASE,
                                10_000,
                                10_000,
                                purchase = 318,
                                due = DUE,
                            ),
                            voided = false,
                            voidable = false,
                        ),
                    ),
                    due = PayableDue.DueOn(DUE),
                    voidedPurchaseLeftCredit = false,
                ),
            ),
            today = TODAY,
            weekStartDay = kotlinx.datetime.DayOfWeek.MONDAY,
        )

        fun PayableDetailUiState.withView(change: (PayableDetailView) -> PayableDetailView) = copy(
            content = PayableDetailContent.Ready(
                change((content as PayableDetailContent.Ready).view),
            ),
        )
    }
}
