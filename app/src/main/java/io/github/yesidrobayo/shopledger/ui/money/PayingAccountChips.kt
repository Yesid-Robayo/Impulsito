package io.github.yesidrobayo.shopledger.ui.money

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow

/**
 * `Cuenta de dinero` en un documento que paga, como un gasto o una compra: un chip por cuenta que se
 * ofrece, con [selectedId] marcada. Tocar la elegida la quita, y el documento queda sin movimiento de
 * dinero. Sin cuentas no dibuja nada: quien no lleva cuentas no ve ningún paso más.
 */
@Composable
fun PayingAccountChips(
    accounts: List<MoneyAccount>,
    selectedId: Long?,
    onChoose: (Long?) -> Unit,
) {
    if (accounts.isEmpty()) return
    Text(
        stringResource(R.string.document_money_account),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val resources = LocalResources.current
    CompactChipRow(
        options = accounts.map { it.id },
        label = { id ->
            val account = accounts.first { it.id == id }
            SeedText.displayName(
                resources,
                SeedTable.MONEY_ACCOUNTS,
                account.systemKey,
                account.details.name,
            )
        },
        selected = setOfNotNull(selectedId),
        onToggle = { id -> onChoose(id.takeUnless { it == selectedId }) },
        multiple = false,
    )
}
