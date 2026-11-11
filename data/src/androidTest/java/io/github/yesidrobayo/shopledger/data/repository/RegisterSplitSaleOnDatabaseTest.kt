package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una venta cobrada con varios métodos sobre la base real, con sus guardas: un pago por método, que
 * suman lo que la venta cobra, y cero filas en cada consulta de invariantes. Un cobro que no suma
 * no deja nada, ni siquiera el cambio de la cuenta de cambios.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class RegisterSplitSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val salePayments by lazy { RoomSalePaymentRepository(database) }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = RoomAppSettingsRepository(database, RestoreLock { false }),
            products = products,
            sales = RoomSaleRepository(database),
            payments = salePayments,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            clock = Clock { AT },
        )
    }

    private suspend fun methodId(systemKey: String): Long =
        count("SELECT id FROM payment_methods WHERE system_key = '$systemKey'")

    private suspend fun soldProduct(): Long = commit {
        val id = products.insert(newProduct("Jabón inventado").copy(salePrice = Money(5_281)))
        ledger.append(openingRow(id, 10))
        id
    }

    private suspend fun request(product: Long, card: Long, cash: Long) = SaleRequest(
        lines = listOf(SaleLineRequest(product, QuantityMilli.ofUnits(1L))),
        paymentMethodId = methodId("CASH"),
        payments = listOf(
            SalePaymentRequest(methodId("CARD"), Money(card)),
            SalePaymentRequest(methodId("CASH"), Money(cash)),
        ),
    )

    @Test
    fun aSaleCollectedWithTwoMethodsPassesEveryGuardAndInvariant() = runBlocking {
        val product = soldProduct()
        val before = changeSeq()

        val result = registerSale(request(product, card = 3_001, cash = 2_280))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(before + 1, changeSeq())
        val payments = salePayments.findBySale(saleId).map { it.content }
        assertEquals(
            listOf(PaymentMethodKind.CARD, PaymentMethodKind.CASH),
            payments.map { it.methodKind },
        )
        assertEquals(listOf(3_001L, 2_280L), payments.map { it.amount.minor })
        assertEquals(
            5_281L,
            count("SELECT amount_due FROM sales WHERE id = $saleId"),
        )
        assertInvariantsHold()
    }

    @Test
    fun aCollectionThatDoesNotAddUpLeavesNothing() = runBlocking {
        val product = soldProduct()
        val before = changeSeq()
        val onHand = requireNotNull(products.findById(product)).onHand

        val result = registerSale(request(product, card = 3_000, cash = 2_280))

        assertEquals(RegisterSaleResult.PaymentsDoNotMatch(Money(5_281), Money(5_280)), result)
        assertEquals(before, changeSeq())
        assertEquals(0L, count("SELECT COUNT(*) FROM sales"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sale_payments"))
        assertEquals(onHand, requireNotNull(products.findById(product)).onHand)
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
