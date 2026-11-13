package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.ModuleUsageSource
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Por qué la cuenta que la persona eligió para un documento no puede mover su dinero. */
enum class AccountIssue {
    /** La cuenta ya no existe. */
    NOT_FOUND,

    /** La cuenta está archivada: no recibe dinero nuevo. */
    ARCHIVED,

    /**
     * La cuenta lleva otra moneda, o la misma con otros decimales. El documento va en la moneda del
     * negocio, y mezclar las dos unidades daría un saldo sin sentido.
     */
    OTHER_CURRENCY,
}

/**
 * El dinero que mueve un documento que la persona registra con una cuenta elegida, como un gasto o
 * una compra: revisar la cuenta, agregar su movimiento y compensarlo al anular el documento. Todo
 * dentro de la transacción del documento, con [MoneyLedgerWriter] como única puerta al libro.
 */
class DocumentMoney(
    private val accounts: MoneyAccountRepository,
    private val ledger: MoneyLedgerRepository,
) {
    private val writer = MoneyLedgerWriter(accounts, ledger)

    /**
     * Por qué la cuenta [accountId] no sirve para un documento en [currency], o `null` si sirve:
     * tiene que existir, estar vigente y llevar la moneda del negocio con sus mismos decimales.
     */
    suspend fun issueOf(accountId: Long, currency: BusinessCurrency): AccountIssue? {
        val account = accounts.findById(accountId) ?: return AccountIssue.NOT_FOUND
        return when {
            account.isArchived -> AccountIssue.ARCHIVED
            account.currency != AccountCurrency.of(currency) -> AccountIssue.OTHER_CURRENCY
            else -> null
        }
    }

    /** Agrega [draft] al libro, en la transacción de quien llama. */
    suspend fun append(
        draft: MoneyMovementDraft,
        time: RecordTime,
        createdAt: Instant,
    ): MoneyAppendResult = writer.append(draft, time, createdAt)

    /** Compensa el dinero que movió el gasto [expenseId], si lo movió. Ver [reverse]. */
    suspend fun reverseExpense(expenseId: Long, time: RecordTime, createdAt: Instant): Boolean =
        reverse(ledger.findByExpense(expenseId), time, createdAt)

    /** Compensa el dinero que movió la compra [purchaseId] al confirmarla. Ver [reverse]. */
    suspend fun reversePurchase(purchaseId: Long, time: RecordTime, createdAt: Instant): Boolean =
        reverse(ledger.findByPurchase(purchaseId), time, createdAt)

    /**
     * Compensa cada uno de [movements] que todavía no tiene su anulación, en su cuenta, con la fecha
     * [time]. Devuelve `false` si el saldo de una cuenta no cabría: quien llama deshace todo.
     */
    suspend fun reverse(
        movements: List<MoneyMovement>,
        time: RecordTime,
        createdAt: Instant,
    ): Boolean {
        for (movement in movements) {
            // Ya se compensó: anular de nuevo lo devolvería dos veces.
            if (ledger.isCompensated(movement.id)) continue
            when (writer.void(movement, time, createdAt)) {
                is MoneyAppendResult.Appended -> Unit

                MoneyAppendResult.Overflow -> return false

                MoneyAppendResult.AccountNotFound ->
                    error("La cuenta de un movimiento siempre existe")
            }
        }
        return true
    }
}

/**
 * Las cuentas que un gasto o una compra ofrece para pagar: las vigentes en la moneda del negocio,
 * con sus mismos decimales, y solo si la persona lleva cuentas. Sin cuentas en uso sale vacía, y el
 * documento no pregunta nada. Vuelve a emitir al cambiar las cuentas, su uso o la moneda.
 */
class ObservePayingAccountsUseCase(
    private val usage: ModuleUsageSource,
    private val settings: AppSettingsRepository,
    private val accounts: MoneyAccountRepository,
) {
    operator fun invoke(): Flow<List<MoneyAccount>> = combine(
        usage.observe().map { BusinessTool.MONEY_ACCOUNTS in it }.distinctUntilChanged(),
        settings.observe().map { AccountCurrency.of(it.regional.currency) }.distinctUntilChanged(),
        accounts.observe(ArchiveFilter.ACTIVE),
    ) { inUse, currency, active ->
        if (inUse) active.filter { it.currency == currency } else emptyList()
    }.distinctUntilChanged()
}
