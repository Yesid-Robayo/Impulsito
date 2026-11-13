package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierDao
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.ObserveOpenCreditPurchasesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprar a plazo y anular sobre la base real, con sus guardas: la compra guarda lo pagado y el
 * vencimiento, el libro del proveedor recibe su cargo y su compensación, cada caso de uso es un solo
 * cambio para el respaldo y cada consulta de invariantes, también la del saldo de los proveedores,
 * queda en cero. Los montos son los del ejemplo de la compra a plazo, y los nombres son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CreditPurchaseOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val suppliers by lazy { RoomSupplierRepository(database) }
    private val supplierLedger by lazy { RoomSupplierLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val registerPurchase by lazy {
        RegisterPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = purchases,
            suppliers = suppliers,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
            supplierLedger = supplierLedger,
        )
    }

    private val voidPurchase by lazy {
        VoidPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = purchases,
            ledger = ledger,
            clock = Clock { LATER_AT },
            supplierLedger = supplierLedger,
        )
    }

    private suspend fun supplier(name: String, termsDays: Int?): Long = commit {
        suppliers.insert(
            SupplierDetails(name, name.lowercase(), null, null, null, termsDays),
            AT,
        )
    }

    private suspend fun buy(
        supplierId: Long?,
        paid: Long?,
        name: String = "Harina inventada",
    ): RegisterPurchaseResult {
        val productId = commit { products.insert(newProduct(name)) }
        val line = PurchaseLineRequest(
            productId,
            QuantityMilli(1_000),
            PurchaseLineAmount.UnitCost(Money(50_000)),
        )
        return registerPurchase(
            PurchaseRequest(listOf(line), supplierId, amountPaid = paid?.let(::Money)),
        )
    }

    @Test
    fun aCreditPurchaseOwesTheRestToItsSupplierAsOneChange() = runBlocking {
        val supplierId = supplier("Molino inventado", termsDays = 30)
        val before = changeSeq()

        val purchaseId = (buy(supplierId, paid = 20_000) as RegisterPurchaseResult.Registered)
            .purchaseId

        // El producto nuevo es un cambio, y la compra con su cargo, uno solo más.
        assertEquals(before + 2, changeSeq())
        val purchase = requireNotNull(purchases.findById(purchaseId)).content
        assertEquals(Money(20_000), purchase.amountPaid)
        assertEquals(purchase.businessDate.plusDays(30), purchase.dueDate)
        val charge = supplierLedger.findByPurchase(purchaseId).single()
        assertEquals(SupplierLedgerEntryType.CREDIT_PURCHASE, charge.content.type)
        assertEquals(Money(30_000), charge.content.amount)
        assertEquals(Money(30_000), charge.content.balanceAfter)
        assertEquals(purchase.dueDate, charge.content.dueDate)
        assertEquals(Money(30_000), requireNotNull(suppliers.findById(supplierId)).balance)
        assertEquals(Money(30_000), supplierLedger.lastBalanceAfter(supplierId))
        assertInvariantsHold()
    }

    @Test
    fun theHistorySaysWhichPurchaseIsOnCreditWithItsDueDateAndWhetherItIsStillOpen() = runBlocking {
        val supplierId = supplier("Molino inventado", termsDays = 30)
        val onCredit = (buy(supplierId, paid = 20_000) as RegisterPurchaseResult.Registered)
            .purchaseId
        val paid = (buy(supplierId, null, "Sal inventada") as RegisterPurchaseResult.Registered)
            .purchaseId

        val rows = RoomPurchaseHistorySource(database)
            .page(PurchaseHistoryFilter(), after = null, limit = 10)
            .associateBy { it.id }
        val due = requireNotNull(purchases.findById(onCredit)).content.dueDate
        assertEquals(true, rows.getValue(onCredit).onCredit)
        assertEquals(due, rows.getValue(onCredit).dueDate)
        assertEquals(false, rows.getValue(paid).onCredit)
        assertEquals(null, rows.getValue(paid).dueDate)
        assertEquals(
            setOf(onCredit),
            ObserveOpenCreditPurchasesUseCase(RoomPayablesSource(database))().first(),
        )
    }

    @Test
    fun voidingACreditPurchaseCompensatesItsChargeOnce() = runBlocking {
        val supplierId = supplier("Molino inventado", termsDays = null)
        val purchaseId = (buy(supplierId, paid = 0) as RegisterPurchaseResult.Registered).purchaseId
        val before = changeSeq()

        assertEquals(
            VoidPurchaseResult.Voided,
            voidPurchase(purchaseId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(
            VoidPurchaseResult.AlreadyVoided,
            voidPurchase(purchaseId, VoidReason.ENTRY_ERROR, null),
        )

        assertEquals(before + 1, changeSeq())
        val charge = supplierLedger.findByPurchase(purchaseId).single()
        assertNull(charge.content.dueDate)
        assertTrue(supplierLedger.isCompensated(charge.id))
        val book = supplierLedger.observeBySupplierOnce(supplierId)
        assertEquals(
            listOf(SupplierLedgerEntryType.CREDIT_PURCHASE, SupplierLedgerEntryType.ENTRY_VOID),
            book.map { it.content.type },
        )
        assertEquals(charge.id, book[1].content.reversesEntryId)
        assertEquals(Money(-50_000), book[1].content.amount)
        assertEquals(Money.ZERO, requireNotNull(suppliers.findById(supplierId)).balance)
        assertInvariantsHold()
    }

    @Test
    fun aPurchasePaidInFullLeavesTheSupplierLedgerEmpty() = runBlocking {
        val supplierId = supplier("Molino inventado", termsDays = 30)

        val purchaseId = (buy(supplierId, paid = null) as RegisterPurchaseResult.Registered)
            .purchaseId

        assertNull(requireNotNull(purchases.findById(purchaseId)).content.dueDate)
        assertEquals(0L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
        assertEquals(Money.ZERO, requireNotNull(suppliers.findById(supplierId)).balance)
    }

    @Test
    fun aCreditPurchaseWithoutSupplierWritesNothing() = runBlocking {
        val before = changeSeq()

        assertEquals(RegisterPurchaseResult.SupplierRequiredForCredit, buy(null, paid = 20_000))

        assertEquals(before + 1, changeSeq())
        assertEquals(0L, count("SELECT COUNT(*) FROM purchases"))
    }

    @Test
    fun aRowThatDoesNotChainIsRefused() = runBlocking {
        val supplierId = supplier("Molino inventado", termsDays = null)
        buy(supplierId, paid = 0)

        val failure = storageFailureOf {
            runner.write {
                supplierLedger.append(adjustment(supplierId, amount = 1_000, balanceAfter = 1_000))
                TxResult.Commit(Unit)
            }
        }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(1L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
        assertInvariantsHold()
    }

    @Test
    fun aFailureMovingTheBalanceLeavesNoRow() = runBlocking {
        val supplierId = supplier("Molino inventado", termsDays = null)
        buy(supplierId, paid = 0)
        val realSuppliers = database.supplierDao()
        val failing = RoomSupplierLedgerRepository(
            database.supplierLedgerEntryDao(),
            object : SupplierDao by realSuppliers {
                override suspend fun setBalance(id: Long, balance: Long, updatedAt: Long): Int =
                    throw IllegalStateException("Falla inyectada")
            },
            RepositoryWrites(database),
        )

        val failed = runCatching {
            runner.write {
                failing.append(adjustment(supplierId, amount = 1_000, balanceAfter = 51_000))
                TxResult.Commit(Unit)
            }
        }

        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertEquals(1L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
        assertEquals(Money(50_000), requireNotNull(suppliers.findById(supplierId)).balance)
        assertInvariantsHold()
    }

    @Test
    fun anAdjustmentThatChainsMovesTheBalance() = runBlocking {
        val supplierId = supplier("Molino inventado", termsDays = null)
        buy(supplierId, paid = 0)

        commit {
            supplierLedger.append(adjustment(supplierId, amount = -2_500, balanceAfter = 47_500))
        }

        assertEquals(Money(47_500), requireNotNull(suppliers.findById(supplierId)).balance)
        assertInvariantsHold()
    }

    private fun adjustment(supplierId: Long, amount: Long, balanceAfter: Long) =
        NewSupplierLedgerEntry(
            supplierId = supplierId,
            type = SupplierLedgerEntryType.ADJUSTMENT,
            amount = Money(amount),
            balanceAfter = Money(balanceAfter),
            note = "Nota inventada",
            occurredAt = AT,
            businessDate = DATE,
            createdAt = AT,
        )

    private suspend fun RoomSupplierLedgerRepository.observeBySupplierOnce(supplierId: Long) =
        observeBySupplier(supplierId).first()

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
