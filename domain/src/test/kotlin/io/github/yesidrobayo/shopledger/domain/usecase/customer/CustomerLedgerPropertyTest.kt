package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sum
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerDueRules
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El saldo del cliente es la suma de su libro, y cada fila deja el saldo anterior más su monto, en
 * cualquier secuencia: cargos, abonos, devoluciones, ajustes y anulaciones al azar, con semilla
 * fija para que una falla se repita igual. Lo pendiente por vencimiento suma lo que se debe.
 */
class CustomerLedgerPropertyTest {
    @Test
    fun `the balance is always the sum of the ledger and the chain holds`() = runTest {
        val world = CustomerLedgerWorld()
        val customers = (1L..3L).onEach { world.seedCustomer(it) }
        val writer = CustomerLedgerWriter(world.ledger)
        val random = Random(SEED)
        var nextDocument = 1L

        repeat(OPERATIONS) { step ->
            val customerId = customers.random(random)
            val amount = Money(random.nextLong(1, 50_000))
            val draft = when (random.nextInt(6)) {
                0 -> entry(customerId, CREDIT_SALE, amount, sale(nextDocument++))

                1 -> entry(customerId, CustomerLedgerEntryType.PAYMENT, -amount)

                2 -> entry(
                    customerId,
                    CustomerLedgerEntryType.RETURN_CREDIT,
                    -amount,
                    CustomerEntrySource.SaleReturn(nextDocument++),
                )

                3 -> entry(
                    customerId,
                    CustomerLedgerEntryType.ADJUSTMENT,
                    if (random.nextBoolean()) amount else -amount,
                    note = "Ajuste $step",
                )

                4 -> entry(customerId, CustomerLedgerEntryType.OPENING_BALANCE, amount)

                else -> null
            }
            world.transactions.write {
                if (draft != null) {
                    writer.append(draft, TIME)
                } else {
                    val candidates = world.ledger.findByCustomer(customerId)
                        .filter { it.type != CustomerLedgerEntryType.ENTRY_VOID }
                        .filterNot { world.ledger.isCompensated(it.id) }
                    candidates.randomOrNull(random)
                        ?.let { writer.reverse(customerId, it.id, null, TIME) }
                }
                TxResult.Commit(Unit)
            }

            for (id in customers) {
                val rows = world.ledger.findByCustomer(id)
                val balance = world.database.tables.value.customers.getValue(id).balance
                assertEquals("paso $step", rows.map { it.amount }.sum(), balance)
                var previous = Money.ZERO
                rows.forEach { row ->
                    val where = "paso $step, fila ${row.id}"
                    assertEquals(where, previous + row.amount, row.balanceAfter)
                    previous = row.balanceAfter
                }
                val open = CustomerDueRules.duesOf(rows).openCharges.map { it.remaining }.sum()
                assertEquals("paso $step", maxOf(balance, Money.ZERO), open)
            }
        }
    }

    private fun entry(
        customerId: Long,
        type: CustomerLedgerEntryType,
        amount: Money,
        source: CustomerEntrySource = CustomerEntrySource.None,
        note: String? = null,
    ) = CustomerEntryDraft(customerId, type, amount, source, null, note)

    private companion object {
        val CREDIT_SALE = CustomerLedgerEntryType.CREDIT_SALE

        fun sale(id: Long) = CustomerEntrySource.Sale(id)
        const val SEED = 20_261_113L
        const val OPERATIONS = 600
        val TIME = RecordTime(
            Instant.fromEpochMilliseconds(1_762_000_000_000L),
            requireNotNull(BusinessDate.parse("2026-11-01")),
        )
    }
}
