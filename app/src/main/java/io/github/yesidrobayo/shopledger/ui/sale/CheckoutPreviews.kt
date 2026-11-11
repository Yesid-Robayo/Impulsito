package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutBalance
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas del cobro, con datos inventados: un método de 80 caracteres, un monto de trece
// cifras, un pago con comisión y recargo, y la variante con pagos que no suman.

private val CASH = SaleMethod(1L, "CASH", null, hasQr = false)
private val LONG_METHOD = SaleMethod(2L, null, PreviewSamples.LONG_NAME, hasQr = true)

private fun previewState(balanceError: Boolean = false): CheckoutUiState = CheckoutUiState(
    content = CheckoutContent.Ready,
    summary = CheckoutSummary(
        amountDue = Money(1_234_567_890_123L),
        subtotal = Money(1_234_567_000_000L),
        writtenAdjustment = Money(-1_000L),
        methodAdjustments = listOf(MethodAdjustment(LONG_METHOD, Money(891_123L))),
    ),
    adjustment = Money(-1_000L),
    payments = listOf(
        CheckoutPaymentRow(method = CASH, amountText = "15000"),
        CheckoutPaymentRow(
            method = LONG_METHOD,
            amountText = "1234567875123",
            covers = Money(1_234_567_875_123L),
            showsFee = true,
            feeText = "355",
            net = Money(1_234_567_874_768L),
        ),
    ),
    balance = if (balanceError) {
        CheckoutBalance.Missing(
            Money(2_000L),
        )
    } else {
        CheckoutBalance.Complete
    },
    due = Money(1_234_567_890_123L),
    balanceError = balanceError,
)

@ThemePreviews
@Composable
private fun CheckoutScreenPreview() {
    PreviewTheme { CheckoutScreen(previewState(), onAction = {}, onBack = {}) }
}

@ThemePreviews
@Composable
private fun CheckoutScreenMissingPreview() {
    PreviewTheme { CheckoutScreen(previewState(balanceError = true), onAction = {}, onBack = {}) }
}

@ThemePreviews
@Composable
private fun CheckoutScreenLoadingPreview() {
    PreviewTheme { CheckoutScreen(CheckoutUiState(), onAction = {}, onBack = {}) }
}
