package io.github.yesidrobayo.shopledger.data.local.payable

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.supplier
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.data.local.money.moneyMovement
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.texts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El libro de proveedores: sus índices y llaves son los del modelo de datos, cada columna vuelve de
 * su lugar, se lee en el orden en que se registró, y lo que guarda basta para rehacer lo que se le
 * debe a cada proveedor y su cadena de `balance_after`, con las consultas de los invariantes del
 * modelo de datos. El ejemplo es el de las reglas del pago y la anulación de una compra a plazo.
 */
@RunWith(AndroidJUnit4::class)
internal class SupplierLedgerDaoTest : LedgerDatabaseTest() {
    private val suppliers get() = database.supplierDao()
    private val ledger get() = database.supplierLedgerEntryDao()

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "index_supplier_ledger_entries_due_date (due_date)",
                "index_supplier_ledger_entries_purchase_id (purchase_id)",
                "unique index_supplier_ledger_entries_reverses_entry_id (reverses_entry_id)",
                "index_supplier_ledger_entries_supplier_id (supplier_id)",
            ),
            database.indexesOf("supplier_ledger_entries"),
        )
        assertEquals(
            listOf(
                "purchase_id -> purchases RESTRICT",
                "reverses_entry_id -> supplier_ledger_entries RESTRICT",
                "supplier_id -> suppliers RESTRICT",
            ),
            database.foreignKeysOf("supplier_ledger_entries"),
        )
        // El pago al proveedor en el libro de dinero apunta a su fila de este libro.
        assertTrue(
            "supplier_ledger_entry_id -> supplier_ledger_entries RESTRICT" in
                database.foreignKeysOf("money_movements"),
        )
    }

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val supplierId = suppliers.insert(supplier("Distribuidora Andina"))
        val purchaseId = database.purchaseDao().insert(purchase(supplierId))
        // Cada columna con un valor distinto dentro de su fila. La devolución a proveedor todavía
        // no tiene tabla ni llave: cualquier id entra.
        val rows =
            listOf(
                supplierEntry(supplierId, "CREDIT_PURCHASE", 30_000, 30_001, purchaseId).copy(
                    dueDate = "2026-01-31",
                    note = "A plazo",
                    occurredAt = 11,
                    createdAt = 12,
                ),
                supplierEntry(supplierId, "RETURN_CREDIT", -1_000, 29_001)
                    .copy(purchaseReturnId = 71, businessDate = "2026-01-02"),
            )

        val stored = rows.map { it.copy(id = ledger.insert(it)) }

        for (row in stored) assertEquals(row, ledger.findById(row.id))
        assertNull(ledger.findById(MISSING_ID))
    }

    @Test
    fun theCreditPurchaseExampleRebuildsWhatIsOwedAndTheChain() = runBlocking {
        val supplierId = suppliers.insert(supplier("Distribuidora Andina"))
        val accountId = database.moneyAccountDao().insert(moneyAccount("Banco"))
        // Compra de 50.000 con 20.000 pagados al confirmarla: lo no pagado va al libro.
        val purchaseId =
            database.purchaseDao().insert(
                purchase(supplierId).copy(
                    netTotal = 50_000,
                    total = 50_000,
                    costTotal = 50_000,
                    amountPaid = 20_000,
                    moneyAccountId = accountId,
                ),
            )
        assertNull(ledger.lastBalanceAfter(supplierId))

        val credit =
            database.appendSupplierEntry(
                supplierEntry(supplierId, "CREDIT_PURCHASE", 30_000, 30_000, purchaseId),
            )
        // Un pago posterior de 10.000 baja lo que se debe a 20.000, y su dinero sale de la cuenta.
        val previous = requireNotNull(ledger.lastBalanceAfter(supplierId))
        val payment =
            database.appendSupplierEntry(
                supplierEntry(supplierId, "PAYMENT", -10_000, previous - 10_000)
                    .copy(occurredAt = LATER, createdAt = LATER),
            )
        database.moneyMovementDao().insert(
            moneyMovement(accountId, "SUPPLIER_PAYMENT", -10_000, -10_000)
                .copy(supplierLedgerEntryId = payment),
        )
        assertEquals(20_000L, suppliers.findById(supplierId)?.balance)

        // Anular la compra compensa su cargo y deja crédito con el proveedor: el pago sigue.
        val charge = ledger.findByPurchase(purchaseId).single()
        assertEquals(credit, charge.id)
        assertFalse(ledger.isCompensated(credit))
        database.appendSupplierEntry(
            supplierEntry(
                supplierId,
                "ENTRY_VOID",
                -charge.amount,
                requireNotNull(ledger.lastBalanceAfter(supplierId)) - charge.amount,
                reversesEntryId = credit,
                occurredAt = MUCH_LATER,
            ),
        )

        val rows = ledger.findBySupplier(supplierId)
        assertEquals(listOf(30_000L, 20_000L, -10_000L), rows.map { it.balanceAfter })
        assertEquals(-10_000L, suppliers.findById(supplierId)?.balance)
        assertEquals(rows.sumOf { it.amount }, suppliers.findById(supplierId)?.balance)
        assertTrue(ledger.isCompensated(credit))
        // El compensatorio no lleva la compra, solo la fila que anula.
        assertEquals(listOf(credit), ledger.findByPurchase(purchaseId).map { it.id })
        assertEquals(credit, rows.last().reversesEntryId)
        assertEquals(emptyList<String>(), invariant(SUPPLIER_BALANCE_SQL))
        assertEquals(emptyList<String>(), invariant(SUPPLIER_CHAIN_SQL))
    }

    @Test
    fun theInvariantQueriesFindAWrongBalanceAndABrokenChain() = runBlocking {
        val supplierId = suppliers.insert(supplier("Distribuidora Andina"))
        val otherId = suppliers.insert(supplier("Mercado Central"))
        database.appendSupplierEntry(supplierEntry(supplierId, "OPENING_BALANCE", 30_000, 30_000))
        // La cadena es por proveedor: la primera fila de otro empieza de cero.
        database.appendSupplierEntry(supplierEntry(otherId, "OPENING_BALANCE", -500, -500))
        assertEquals(emptyList<String>(), invariant(SUPPLIER_CHAIN_SQL))

        suppliers.setBalance(supplierId, balance = 29_000, updatedAt = MUCH_LATER)
        assertEquals(listOf("$supplierId"), invariant(SUPPLIER_BALANCE_SQL))
        // La edición del proveedor no toca lo que se le debe.
        suppliers.update(supplierId, "Distribuidora", "distribuidora", null, null, null, 30, LATER)
        assertEquals(29_000L, suppliers.findById(supplierId)?.balance)
        assertEquals(30L, suppliers.findById(supplierId)?.paymentTermsDays)

        val broken = ledger.insert(supplierEntry(supplierId, "PAYMENT", -1_000, 25_000))
        assertEquals(listOf("$broken"), invariant(SUPPLIER_CHAIN_SQL))
    }

    @Test
    fun theLedgerIsReadInTheOrderItWasRecordedNotByItsClock() = runBlocking {
        val supplierId = suppliers.insert(supplier("Distribuidora Andina"))
        val first =
            supplierEntry(supplierId, "OPENING_BALANCE", 30_000, 30_000, occurredAt = MUCH_LATER)
        val firstId = ledger.insert(first)
        val second = supplierEntry(supplierId, "PAYMENT", -5_000, 25_000, occurredAt = CREATED_AT)
        val secondId = ledger.insert(second)
        val expected = listOf(first.copy(id = firstId), second.copy(id = secondId))

        assertEquals(expected, ledger.findBySupplier(supplierId))
        val emissions = Emissions(this, ledger.observeBySupplier(supplierId))
        try {
            emissions.awaitValue(expected)
            val third = supplierEntry(supplierId, "PAYMENT", -5_000, 20_000)
            val thirdId = ledger.insert(third)
            emissions.awaitValue(expected + third.copy(id = thirdId))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun aSupplierWithEntriesIsNotDeletedAndNoEntryIsAnOrphan() = runBlocking {
        val supplierId = suppliers.insert(supplier("Distribuidora Andina"))
        ledger.insert(supplierEntry(supplierId, "OPENING_BALANCE", 30_000, 30_000))
        val accountId = database.moneyAccountDao().insert(moneyAccount("Banco"))

        constraintFailure(FOREIGN_KEY) { suppliers.delete(supplierId) }
        constraintFailure(FOREIGN_KEY) {
            ledger.insert(supplierEntry(MISSING_ID, "OPENING_BALANCE", 30_000, 30_000))
        }
        constraintFailure(FOREIGN_KEY) {
            ledger.insert(
                supplierEntry(supplierId, "CREDIT_PURCHASE", 1, 30_001, purchaseId = MISSING_ID),
            )
        }
        constraintFailure(FOREIGN_KEY) {
            database.moneyMovementDao().insert(
                moneyMovement(accountId, "SUPPLIER_PAYMENT", -1, -1)
                    .copy(supplierLedgerEntryId = MISSING_ID),
            )
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
    }

    private suspend fun invariant(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private companion object {
        const val MISSING_ID = 999L
    }
}
