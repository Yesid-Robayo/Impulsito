package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.PayablesSource
import io.github.yesidrobayo.shopledger.domain.port.SupplierAccount
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Qué compras a plazo siguen abiertas: un pago cubre primero el cargo más antiguo, una compra
 * anulada sale sola y un libro con cifras que no caben no aporta nada. Montos inventados.
 */
class ObserveOpenCreditPurchasesUseCaseTest {
    private val accounts = MutableStateFlow<List<SupplierAccount>>(emptyList())
    private val observe = ObserveOpenCreditPurchasesUseCase(PayablesSource { accounts })

    @Test
    fun `a payment covers the oldest purchase first and leaves the newer one open`() = runTest {
        // Compras 10 de 5000 y 11 de 3000; un pago de 6000 salda la 10 y deja 2000 de la 11.
        accounts.value = listOf(
            account(
                1,
                charge(1, purchase = 10, amount = 5_000),
                charge(2, purchase = 11, amount = 3_000),
                entry(3, SupplierLedgerEntryType.PAYMENT, -6_000),
            ),
        )

        assertEquals(setOf(11L), observe().first())
    }

    @Test
    fun `a voided purchase is no longer open and other suppliers add theirs`() = runTest {
        accounts.value = listOf(
            account(
                1,
                charge(1, purchase = 10, amount = 5_000),
                entry(2, SupplierLedgerEntryType.ENTRY_VOID, -5_000, reverses = 1),
            ),
            account(2, charge(3, purchase = 20, amount = 1_000)),
        )

        assertEquals(setOf(20L), observe().first())
    }

    @Test
    fun `a ledger whose sums do not fit adds nothing`() = runTest {
        accounts.value = listOf(
            account(
                1,
                charge(1, purchase = 10, amount = Long.MAX_VALUE),
                entry(2, SupplierLedgerEntryType.PAYMENT, Long.MIN_VALUE + 1),
                entry(3, SupplierLedgerEntryType.PAYMENT, -5),
            ),
        )

        assertEquals(emptySet<Long>(), observe().first())
    }

    private fun account(id: Long, vararg entries: SupplierLedgerEntry) = SupplierAccount(
        Supplier(
            id = id,
            details = SupplierDetails("Proveedor $id", "proveedor $id", null, null, null, null),
            balance = Money.ZERO,
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        ),
        entries.toList(),
    )

    private fun charge(id: Long, purchase: Long, amount: Long) =
        entry(id, SupplierLedgerEntryType.CREDIT_PURCHASE, amount, purchase = purchase)

    private fun entry(
        id: Long,
        type: SupplierLedgerEntryType,
        amount: Long,
        purchase: Long? = null,
        reverses: Long? = null,
    ) = SupplierLedgerEntry(
        id,
        NewSupplierLedgerEntry(
            supplierId = 1,
            type = type,
            amount = Money(amount),
            balanceAfter = Money(amount),
            purchaseId = purchase,
            reversesEntryId = reverses,
            occurredAt = AT,
            businessDate = DATE,
            createdAt = AT,
        ),
    )

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(0)
        val DATE = BusinessDate(LocalDate(2026, 11, 1))
    }
}
