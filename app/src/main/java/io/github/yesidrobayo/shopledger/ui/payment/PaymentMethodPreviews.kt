package io.github.yesidrobayo.shopledger.ui.payment

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodOptions
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant

// Vistas previas de la lista y del formulario de métodos de pago, con datos inventados y sin marcas:
// los seis iniciales, uno con nombre de 80 caracteres, comisión con monto largo y uno archivado.

private val AT = Instant.fromEpochMilliseconds(1_700_000_000_000L)

private fun sample(
    id: Long,
    key: String?,
    name: String?,
    kind: PaymentMethodKind,
    fee: Long = 0,
    fixed: Long = 0,
    days: Int = 0,
    archived: Boolean = false,
) = PaymentMethodRecord(
    id = id,
    systemKey = key,
    details =
        PaymentMethodDetails(
            name = name,
            normalizedName = name?.lowercase(),
            kind = kind,
            feeRate = BasisPoints(fee),
            feeFixed = Money(fixed),
            settlementDays = days,
            priceAdjustment = BasisPoints.ZERO,
            moneyAccountId = null,
            instructionsText = null,
            instructionsMediaId = null,
        ),
    sortOrder = id,
    archivedAt = if (archived) AT else null,
    createdAt = AT,
    updatedAt = AT,
)

internal val SAMPLE_ACTIVE =
    listOf(
        sample(1, "CASH", null, PaymentMethodKind.CASH),
        sample(2, "CARD", null, PaymentMethodKind.CARD, fee = 250, fixed = 100, days = 2),
        sample(3, "TRANSFER", null, PaymentMethodKind.TRANSFER),
        sample(
            7,
            null,
            PreviewSamples.LONG_NAME,
            PaymentMethodKind.WALLET,
            fee = 100,
            fixed = PreviewSamples.LONG_AMOUNT.minor,
        ),
        sample(5, "ON_ACCOUNT", null, PaymentMethodKind.ON_ACCOUNT),
        sample(6, "OTHER", null, PaymentMethodKind.OTHER),
    )

internal val SAMPLE_ARCHIVED =
    listOf(sample(4, "WALLET", null, PaymentMethodKind.WALLET, archived = true))

@ThemePreviews
@Composable
private fun PaymentMethodsListPreview() {
    PreviewTheme {
        PaymentMethodsScreen(
            uiState = PaymentMethodsUiState.Ready(
                SAMPLE_ACTIVE,
                SAMPLE_ARCHIVED,
                archivedExpanded = true,
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PaymentMethodsEmptyPreview() {
    PreviewTheme {
        PaymentMethodsScreen(
            uiState = PaymentMethodsUiState.Ready(
                emptyList(),
                SAMPLE_ARCHIVED,
            ),
            onAction = {
            },
        )
    }
}

@ThemePreviews
@Composable
private fun PaymentMethodsSortingPreview() {
    PreviewTheme {
        PaymentMethodsScreen(
            uiState =
                PaymentMethodsUiState.Ready(
                    SAMPLE_ACTIVE,
                    emptyList(),
                    sorting = SortingState(SAMPLE_ACTIVE, SortingStatus.ListChanged),
                ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PaymentMethodFormPreview() {
    PreviewTheme {
        PaymentMethodFormScreen(
            uiState =
                PaymentMethodFormUiState.Editing(
                    methodId = 2,
                    systemKey = "CARD",
                    form =
                        PaymentMethodForm(
                            kind = PaymentMethodKind.CARD,
                            feeRate = "2,5",
                            feeFixed = "100",
                            settlementDays = "2",
                            priceAdjustment = "-1",
                            instructions = PreviewSamples.LONG_NAME,
                        ),
                    options = PaymentMethodOptions(
                        SAMPLE_ACTIVE[1],
                        hasSalePayments = true,
                        activeCount = 6,
                    ),
                    errors = mapOf(FormField.FEE_RATE to FieldProblem.FEE_RATE_RANGE),
                    notice = FormNotice.SaveFailed(SaveProblem.OUT_OF_SPACE),
                ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PaymentMethodFormOnAccountPreview() {
    PreviewTheme {
        PaymentMethodFormScreen(
            uiState =
                PaymentMethodFormUiState.Editing(
                    methodId = null,
                    systemKey = null,
                    form = PaymentMethodForm(
                        name = PreviewSamples.METHOD_WALLET,
                        nameEdited = true,
                        kind = PaymentMethodKind.ON_ACCOUNT,
                    ),
                    options = null,
                ),
            onAction = {},
        )
    }
}
