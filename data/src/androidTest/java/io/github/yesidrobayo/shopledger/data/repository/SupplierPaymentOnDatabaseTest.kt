package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.payable.VoidSupplierPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.VoidSupplierPaymentUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pagar a un proveedor desde una cuenta y anular el pago sobre la base real, con sus guardas: el
 * `PAYMENT` y el `SUPPLIER_PAYMENT` pasan cada guarda y quedan unidos por su llave, cada caso de
 * uso es un solo cambio para el respaldo, y las consultas de invariantes de los dos libros quedan
 * en cero. Los nombres y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SupplierPaymentOnDatabaseTest : RepositoryTest() {
    private val suppliers by lazy { RoomSupplierRepository(database) }
    private val ledger by lazy { RoomSupplierLedgerRepository(database) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val pay by lazy {
        PaySupplierUseCase(runner, settings, suppliers, ledger, Clock { AT }, accounts, money)
    }

    private val void by lazy {
        VoidSupplierPaymentUseCase(runner, settings, ledger, Clock { LATER_AT }, accounts, money)
    }

    private suspend fun supplier(): Long = commit {
        suppliers.insert(
            SupplierDetails("Molino inventado", "molino inventado", null, null, null, null),
            AT,
        )
    }

    /** Una cuenta de banco en la moneda del negocio. */
    private suspend fun bank(): Long = commit {
        accounts.insert(
            NewMoneyAccount(
                MoneyAccountDetails("Banco inventado", "banco inventado", MoneyAccountKind.BANK),
                AccountCurrency.of(settings.get().regional.currency),
                AT,
            ),
        )
    }

    @Test
    fun aPaymentFromAnAccountWritesBothLedgersAsOneChange() = runBlocking {
        val supplierId = supplier()
        val bank = bank()
        val before = changeSeq()

        val result = pay(SupplierPaymentRequest(supplierId, Money(10_000), moneyAccountId = bank))

        val entryId = (result as PaySupplierResult.Paid).entryId
        assertEquals(before + 1, changeSeq())
        assertEquals(
            SupplierLedgerEntryType.PAYMENT,
            requireNotNull(ledger.findById(entryId)).content.type,
        )
        val movement = money.findBySupplierLedgerEntry(entryId).single()
        assertEquals(MoneyMovementType.SUPPLIER_PAYMENT, movement.type)
        assertEquals(Money(-10_000), movement.amount)
        assertEquals(Money(-10_000), requireNotNull(accounts.findById(bank)).balance)
        assertEquals(Money(-10_000), requireNotNull(suppliers.findById(supplierId)).balance)
        assertInvariantsHold()
    }

    @Test
    fun voidingThePaymentCompensatesBothLedgersAsOneChange() = runBlocking {
        val supplierId = supplier()
        val bank = bank()
        val entryId = (
            pay(SupplierPaymentRequest(supplierId, Money(10_000), moneyAccountId = bank))
                as PaySupplierResult.Paid
            ).entryId
        val before = changeSeq()

        assertEquals(VoidSupplierPaymentResult.Voided, void(entryId))
        assertEquals(VoidSupplierPaymentResult.AlreadyVoided, void(entryId))

        assertEquals(before + 1, changeSeq())
        val movement = money.findBySupplierLedgerEntry(entryId).single()
        assertEquals(true, money.isCompensated(movement.id))
        assertEquals(true, ledger.isCompensated(entryId))
        assertEquals(Money.ZERO, requireNotNull(accounts.findById(bank)).balance)
        assertEquals(Money.ZERO, requireNotNull(suppliers.findById(supplierId)).balance)
        assertInvariantsHold()
    }

    @Test
    fun aPaymentWithoutAccountLeavesTheMoneyLedgerEmpty() = runBlocking {
        val supplierId = supplier()

        val result = pay(SupplierPaymentRequest(supplierId, Money(4_000)))

        val entryId = (result as PaySupplierResult.Paid).entryId
        assertEquals(emptyList<Any>(), money.findBySupplierLedgerEntry(entryId))
        assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
