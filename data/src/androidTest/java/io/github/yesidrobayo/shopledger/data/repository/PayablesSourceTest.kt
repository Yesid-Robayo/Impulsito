package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.SupplierAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las cuentas por pagar sobre la base real: solo salen los proveedores con saldo distinto de cero,
 * también archivados, con su libro en orden, y la lista vuelve a emitir al pagar. Los nombres y los
 * montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PayablesSourceTest : RepositoryTest() {
    private val suppliers by lazy { RoomSupplierRepository(database) }
    private val ledger by lazy { RoomSupplierLedgerRepository(database) }
    private val source by lazy { RoomPayablesSource(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private suspend fun supplier(name: String): Long = commit {
        suppliers.insert(SupplierDetails(name, name.lowercase(), null, null, null, null), AT)
    }

    /** Agrega una fila encadenada, como lo haría un caso de uso. */
    private suspend fun append(supplierId: Long, type: SupplierLedgerEntryType, amount: Long) =
        commit {
            settings.lockCurrency(AT)
            val previous = ledger.lastBalanceAfter(supplierId) ?: Money.ZERO
            ledger.append(
                NewSupplierLedgerEntry(
                    supplierId = supplierId,
                    type = type,
                    amount = Money(amount),
                    balanceAfter = previous + Money(amount),
                    dueDate = DATE.plusDays(30).takeIf { amount > 0 },
                    note = "Nota inventada",
                    occurredAt = AT,
                    businessDate = DATE,
                    createdAt = AT,
                ),
            )
        }

    @Test
    fun onlySuppliersWithABalanceAreListedWithTheirLedger() = runBlocking {
        val owed = supplier("Molino inventado")
        val settled = supplier("Granja inventada")
        val inFavor = supplier("Taller inventado")
        val untouched = supplier("Bodega inventada")
        append(owed, SupplierLedgerEntryType.OPENING_BALANCE, 5_000)
        append(owed, SupplierLedgerEntryType.ADJUSTMENT, 1_000)
        append(settled, SupplierLedgerEntryType.OPENING_BALANCE, 2_000)
        append(settled, SupplierLedgerEntryType.ADJUSTMENT, -2_000)
        append(inFavor, SupplierLedgerEntryType.ADJUSTMENT, -700)
        commit { suppliers.setArchivedAt(inFavor, AT, AT) }

        val accounts = source.observe().first()

        // Por nombre normalizado: molino antes que taller.
        assertEquals(listOf(owed, inFavor), accounts.map { it.supplier.id })
        assertEquals(listOf(Money(5_000), Money(1_000)), amountsOf(accounts[0]))
        assertEquals(DATE.plusDays(30), accounts[0].entries[0].content.dueDate)
        assertTrue(accounts[1].supplier.isArchived)
        assertTrue(accounts.none { it.supplier.id == settled || it.supplier.id == untouched })
    }

    @Test
    fun theListEmitsAgainWhenADebtIsPaid() = runBlocking {
        val owed = supplier("Molino inventado")
        append(owed, SupplierLedgerEntryType.OPENING_BALANCE, 5_000)

        source.observe().test {
            assertEquals(listOf(owed), awaitItem().map { it.supplier.id })
            append(owed, SupplierLedgerEntryType.PAYMENT, -5_000)
            awaitValue(emptyList())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun amountsOf(account: SupplierAccount) = account.entries.map { it.content.amount }
}
