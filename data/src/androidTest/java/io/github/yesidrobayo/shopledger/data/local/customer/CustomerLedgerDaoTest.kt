package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.local.texts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los libros del cliente y de puntos: cada columna vuelve de su lugar, se leen en el orden en que
 * se registraron, y lo que guardan basta para rehacer el saldo del cliente y la cadena de
 * `balance_after` sumando el libro, con las consultas de los invariantes del modelo de datos. Los
 * ejemplos son los de las reglas del libro del cliente y de la anulación de puntos.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerLedgerDaoTest : LedgerDatabaseTest() {
    private val customers get() = database.customerDao()
    private val ledger get() = database.customerLedgerEntryDao()
    private val points get() = database.loyaltyLedgerEntryDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val customerId = customers.insert(customer("Ana"))
        val saleId = database.saleDao().insert(sale(priceListId).copy(customerId = customerId))
        val saleReturnId = database.saleReturnDao().insert(saleReturn(saleId))
        // Cada columna con un valor distinto dentro de su fila, para que dos columnas cruzadas no
        // pasen.
        val entries =
            listOf(
                customerEntry(customerId, "CREDIT_SALE", 5_000, 5_001, saleId = saleId)
                    .copy(dueDate = "2026-02-15", note = "Fiado", occurredAt = 11, createdAt = 12),
                customerEntry(customerId, "RETURN_CREDIT", -1_000, 4_001)
                    .copy(saleReturnId = saleReturnId, businessDate = "2026-01-02"),
            )
        val loyalty =
            loyaltyEntry(customerId, "EARN", 12, 13, saleId = saleId)
                .copy(note = "Compra grande", occurredAt = 21, createdAt = 22)

        val stored = entries.map { it.copy(id = ledger.insert(it)) }
        val storedPoints = loyalty.copy(id = points.insert(loyalty))

        for (entry in stored) assertEquals(entry, ledger.findById(entry.id))
        assertEquals(storedPoints, points.findById(storedPoints.id))
        assertNull(ledger.findById(MISSING_ID))
        assertNull(points.findById(MISSING_ID))
    }

    @Test
    fun theCustomerLedgerExampleRebuildsTheBalanceAndTheChain() = runBlocking {
        val customerId = customers.insert(customer("Ana"))
        val sales = List(3) { database.saleDao().insert(sale(priceListId)) }
        assertNull(ledger.lastBalanceAfter(customerId))

        // El ejemplo de la regla: cargos a crédito, abonos, un saldo a favor y la anulación de un
        // abono. Cada fila toma su saldo de la anterior, como lo hará el repositorio.
        val steps =
            listOf(
                Step("CREDIT_SALE", 5_000, 5_000, saleId = sales[0]),
                Step("PAYMENT", -2_000, 3_000),
                Step("CREDIT_SALE", 1_500, 4_500, saleId = sales[1]),
                Step("PAYMENT", -6_000, -1_500),
                Step("CREDIT_SALE", 1_000, -500, saleId = sales[2]),
                Step("ENTRY_VOID", 6_000, 5_500, reverses = 4),
            )
        val ids = mutableListOf<Long>()
        for (step in steps) {
            val previous = ledger.lastBalanceAfter(customerId) ?: 0
            assertEquals(step.balanceAfter, previous + step.amount)
            val reverses = step.reverses?.let { ids[it - 1] }
            ids +=
                database.appendCustomerEntry(
                    customerEntry(
                        customerId,
                        step.type,
                        step.amount,
                        previous + step.amount,
                        saleId = step.saleId,
                        reversesEntryId = reverses,
                    ),
                )
        }

        val rows = ledger.findByCustomer(customerId)
        assertEquals(ids, rows.map { it.id })
        assertEquals(steps.map { it.balanceAfter }, rows.map { it.balanceAfter })
        assertEquals(5_500L, customers.findById(customerId)?.balance)
        assertEquals(rows.sumOf { it.amount }, customers.findById(customerId)?.balance)
        assertEquals(5_500L, ledger.lastBalanceAfter(customerId))
        assertEquals(emptyList<String>(), invariant(CUSTOMER_BALANCE_SQL))
        assertEquals(emptyList<String>(), invariant(CUSTOMER_CHAIN_SQL))
        // La anulación de una venta encuentra su cargo, y un abono anulado no se anula otra vez.
        assertEquals(listOf(ids[0]), ledger.findBySale(sales[0]).map { it.id })
        assertTrue(ledger.isCompensated(ids[3]))
        assertFalse(ledger.isCompensated(ids[1]))
    }

    @Test
    fun theInvariantQueriesFindAWrongBalanceAndABrokenChain() = runBlocking {
        val ana = customers.insert(customer("Ana"))
        val beatriz = customers.insert(customer("Beatriz"))
        database.appendCustomerEntry(customerEntry(ana, "OPENING_BALANCE", 5_000, 5_000))
        // La cadena es por cliente: la primera fila de otro cliente empieza de cero.
        database.appendCustomerEntry(customerEntry(beatriz, "OPENING_BALANCE", -700, -700))
        assertEquals(emptyList<String>(), invariant(CUSTOMER_CHAIN_SQL))

        // Un saldo de cabecera que no es la suma se detecta, y se repara sin tocar el libro.
        customers.setBalance(ana, balance = 4_000, updatedAt = MUCH_LATER)
        assertEquals(listOf("$ana"), invariant(CUSTOMER_BALANCE_SQL))
        customers.setBalance(ana, balance = 5_000, updatedAt = MUCH_LATER)
        assertEquals(emptyList<String>(), invariant(CUSTOMER_BALANCE_SQL))

        // Una fila cuyo saldo no sigue al anterior entra, porque la guarda no recalcula la cadena,
        // pero su consulta la señala sin ambigüedad: el libro no se reescribe, se avisa.
        val broken = ledger.insert(customerEntry(ana, "PAYMENT", -1_000, 3_000))
        assertEquals(listOf("$broken"), invariant(CUSTOMER_CHAIN_SQL))
    }

    @Test
    fun theLedgerIsReadInTheOrderItWasRecordedNotByItsClock() = runBlocking {
        val customerId = customers.insert(customer("Ana"))
        val otherId = customers.insert(customer("Beatriz"))
        // El reloj del teléfono retrocedió: la segunda fila dice ser anterior a la primera.
        val first =
            customerEntry(customerId, "OPENING_BALANCE", 5_000, 5_000, occurredAt = MUCH_LATER)
        val firstId = ledger.insert(first)
        ledger.insert(customerEntry(otherId, "OPENING_BALANCE", 100, 100))
        val second = customerEntry(customerId, "PAYMENT", -2_000, 3_000, occurredAt = CREATED_AT)
        val secondId = ledger.insert(second)
        val expected = listOf(first.copy(id = firstId), second.copy(id = secondId))

        assertEquals(expected, ledger.findByCustomer(customerId))
        assertEquals(3_000L, ledger.lastBalanceAfter(customerId))
        val emissions = Emissions(this, ledger.observeByCustomer(customerId))
        try {
            emissions.awaitValue(expected)

            val third = customerEntry(customerId, "PAYMENT", -1_000, 2_000)
            val thirdId = ledger.insert(third)
            emissions.awaitValue(expected + third.copy(id = thirdId))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun thePointsExampleRebuildsThePointsBalanceAndTheChain() = runBlocking {
        val customerId = customers.insert(customer("Ana"))
        val saleId = database.saleDao().insert(sale(priceListId).copy(customerId = customerId))
        assertNull(points.lastBalanceAfter(customerId))

        // El ejemplo de la regla: saldo de 150, una venta que canjea 100 y gana 12 deja 62, y
        // anularla vuelve a 150.
        val opening = database.appendLoyaltyEntry(loyaltyEntry(customerId, "ADJUSTMENT", 150, 150))
        val redeem =
            database.appendLoyaltyEntry(
                loyaltyEntry(customerId, "REDEEM", -100, 50, saleId = saleId),
            )
        val earn =
            database.appendLoyaltyEntry(loyaltyEntry(customerId, "EARN", 12, 62, saleId = saleId))
        assertEquals(62L, customers.findById(customerId)?.pointsBalance)

        val sold = points.findBySale(saleId)
        assertEquals(listOf(redeem, earn), sold.map { it.id })
        for (row in sold.reversed()) {
            val previous = points.lastBalanceAfter(customerId) ?: 0
            database.appendLoyaltyEntry(
                loyaltyEntry(
                    customerId,
                    "ENTRY_VOID",
                    -row.points,
                    previous - row.points,
                    reversesEntryId = row.id,
                ),
            )
        }

        val rows = points.findByCustomer(customerId)
        assertEquals(listOf(150L, 50L, 62L, 50L, 150L), rows.map { it.balanceAfter })
        assertEquals(opening, rows.first().id)
        assertEquals(150L, customers.findById(customerId)?.pointsBalance)
        assertEquals(rows.sumOf { it.points }, customers.findById(customerId)?.pointsBalance)
        assertTrue(points.isCompensated(redeem))
        assertFalse(points.isCompensated(opening))
        assertEquals(emptyList<String>(), invariant(POINTS_BALANCE_SQL))
        assertEquals(emptyList<String>(), invariant(POINTS_CHAIN_SQL))
        // Los puntos no son dinero: el saldo del cliente no se movió.
        assertEquals(0L, customers.findById(customerId)?.balance)

        customers.setPointsBalance(customerId, pointsBalance = 149, updatedAt = MUCH_LATER)
        assertEquals(listOf("$customerId"), invariant(POINTS_BALANCE_SQL))
        val emissions = Emissions(this, points.observeByCustomer(customerId))
        try {
            emissions.awaitValue(rows)
        } finally {
            emissions.stop()
        }
    }

    private suspend fun invariant(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    /** Un paso del ejemplo: tipo, monto, saldo esperado, venta y número de la fila que anula. */
    private class Step(
        val type: String,
        val amount: Long,
        val balanceAfter: Long,
        val saleId: Long? = null,
        val reverses: Int? = null,
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}
