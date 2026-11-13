package io.github.yesidrobayo.shopledger.ui.payable

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierDetailContent
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierDetailScreen
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierDetailUiState
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierSamples
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Con el modo discreto, lo que se le debe a un proveedor no aparece en el libro de cuentas por pagar
 * ni en su ficha: ni en el texto de la pantalla ni en lo que lee TalkBack. Sin el modo, sí. Las
 * cifras son inventadas y lo bastante raras para no confundirse con una fecha.
 */
@RunWith(AndroidJUnit4::class)
class PayableDiscreetModeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theLedgerOfAPayableHidesEveryFigure() {
        show(discreet = false) { PayableDetailScreen(DETAIL, {}) }
        assertTrue(figures().fetchSemanticsNodes().isNotEmpty())

        show(discreet = true) {
            PayableDetailScreen(DETAIL, {})
            SupplierPaymentSheetHost(
                DETAIL.copy(payment = SHEET),
                SHEET,
            ) {}
        }
        assertEquals(0, figures().fetchSemanticsNodes().size)
        assertEquals(0, figures(unmerged = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theSupplierCardHidesWhatIsOwed() {
        val state = SupplierDetailUiState(
            SupplierDetailContent.Ready(
                SupplierSamples.overview().let { overview ->
                    overview.copy(supplier = overview.supplier.copy(balance = Money(OWED)))
                },
            ),
            payableDue = PayableDue.DueOn(DUE),
        )
        show(discreet = false) { SupplierDetailScreen(state, {}) }
        assertTrue(figures().fetchSemanticsNodes().isNotEmpty())

        show(discreet = true) { SupplierDetailScreen(state, {}) }
        assertEquals(0, figures().fetchSemanticsNodes().size)
        assertEquals(0, figures(unmerged = true).fetchSemanticsNodes().size)
    }

    /** Los nodos que dicen la cifra, en el texto o en lo que lee TalkBack. */
    private fun figures(unmerged: Boolean = false): SemanticsNodeInteractionCollection =
        compose.onAllNodes(
            hasText(OWED_TEXT, substring = true) or
                hasContentDescription(OWED_TEXT, substring = true),
            useUnmergedTree = unmerged,
        )

    private fun show(discreet: Boolean, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = false) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        /** Lo que se debe: sus cifras no salen en ninguna fecha ni en otro dato de la pantalla. */
        const val OWED = 987_654L
        const val OWED_TEXT = "987"
        val AT = Instant.parse("2026-11-01T12:00:00Z")
        val TODAY = requireNotNull(BusinessDate.parse("2026-11-10"))
        val DUE = requireNotNull(BusinessDate.parse("2026-11-20"))

        val SUPPLIER = Supplier(
            id = 1,
            details = SupplierDetails(
                "Proveedor inventado",
                "proveedor inventado",
                null,
                null,
                null,
                30,
            ),
            balance = Money(OWED),
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )

        fun entry(id: Long, type: SupplierLedgerEntryType, amount: Long, after: Long) =
            SupplierLedgerEntry(
                id,
                NewSupplierLedgerEntry(
                    supplierId = 1,
                    type = type,
                    amount = Money(amount),
                    balanceAfter = Money(after),
                    purchaseId = 318L.takeIf { type == SupplierLedgerEntryType.CREDIT_PURCHASE },
                    occurredAt = AT,
                    businessDate = TODAY,
                    createdAt = AT,
                ),
            )

        val DETAIL = PayableDetailUiState(
            content = PayableDetailContent.Ready(
                PayableDetailView(
                    supplier = SUPPLIER,
                    lines = listOf(
                        PayableLine(
                            entry(1, SupplierLedgerEntryType.CREDIT_PURCHASE, OWED, OWED),
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

        /** Un pago a medio escribir que deja la deuda entera: lo que queda, también se oculta. */
        val SHEET = SupplierPaymentSheet(
            amountText = "1",
            preview = PayablePreview.Remaining(Money(OWED)),
        )
    }
}
