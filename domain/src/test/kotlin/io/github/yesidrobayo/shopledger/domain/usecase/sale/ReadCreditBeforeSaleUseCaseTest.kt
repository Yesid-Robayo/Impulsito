package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWorld
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El saldo a favor de antes de una venta, leído del libro del cliente sin escribir nada: lo que
 * el comprobante muestra como anticipo aplicado. Los montos son inventados.
 */
class ReadCreditBeforeSaleUseCaseTest {
    private val world = CustomerLedgerWorld().apply { seedCustomer(CUSTOMER) }
    private val writer = CustomerLedgerWriter(world.ledger)
    private val read = ReadCreditBeforeSaleUseCase(world.transactions, world.ledger)

    /**
     * Un anticipo de 5.000 y una venta que carga 5.000 a crédito: antes de la venta había 5.000 a
     * favor, aunque el cliente ya quedó en cero. Una segunda venta a crédito ya no encuentra nada.
     */
    @Test
    fun `a sale finds the credit balance its charge consumed, and the next one finds none`() =
        runTest {
            append(CustomerLedgerEntryType.PAYMENT, -5_000L, CustomerEntrySource.None)
            append(CustomerLedgerEntryType.CREDIT_SALE, 5_000L, CustomerEntrySource.Sale(7L))
            append(CustomerLedgerEntryType.CREDIT_SALE, 2_000L, CustomerEntrySource.Sale(8L))
            val before = world.database.read()

            assertEquals(Money(5_000L), read(7L))
            assertEquals(Money.ZERO, read(8L))
            assertEquals(before, world.database.read())
        }

    /** Una venta sin cargo a crédito, como una de contado, no tiene saldo a favor que mostrar. */
    @Test
    fun `a sale without a charge has no credit balance`() = runTest {
        append(CustomerLedgerEntryType.PAYMENT, -3_000L, CustomerEntrySource.None)

        assertEquals(Money.ZERO, read(9L))
    }

    private suspend fun append(
        type: CustomerLedgerEntryType,
        amount: Long,
        source: CustomerEntrySource,
    ) {
        val draft = CustomerEntryDraft(CUSTOMER, type, Money(amount), source, null, null)
        val time = RecordTime(CustomerLedgerWorld.NOW, DATE)
        world.transactions.write { TxResult.Commit(writer.append(draft, time)) }
    }

    private companion object {
        const val CUSTOMER = 3L
        val DATE = requireNotNull(BusinessDate.parse("2026-11-01"))
    }
}
