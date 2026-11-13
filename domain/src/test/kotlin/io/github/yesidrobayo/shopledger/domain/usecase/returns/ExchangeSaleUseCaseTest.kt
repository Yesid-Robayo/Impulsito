package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cambiar productos de una venta: la venta nueva y la devolución de cambio que la nombra entran en
 * una sola transacción, y en la cuenta queda solo la diferencia. Un rechazo de cualquiera de las dos
 * no deja ninguna. Los valores esperados están escritos a mano.
 */
class ExchangeSaleUseCaseTest {
    private val world = SaleWorld().apply {
        addMoneyAccount(ACCOUNT, balance = 10_000)
        // El efectivo entra en la misma cuenta de la que sale el crédito.
        addMethod(method(moneyAccountId = ACCOUNT))
        addProduct(
            product(
                SOAP,
                salePrice = 2_000,
                onHandMilli = 5_000,
                averageCost = 1_000,
                stockValue = 5_000,
            ),
        )
        addProduct(
            product(
                RICE,
                salePrice = 3_000,
                onHandMilli = 5_000,
                averageCost = 1_500,
                stockValue = 7_500,
            ),
        )
    }

    private suspend fun soapSale(): Long {
        val request =
            SaleRequest(listOf(SaleLineRequest(SOAP, QuantityMilli(2_000))), SaleWorld.CASH)
        return (world.registerSale()(request) as RegisterSaleResult.Registered).saleId
    }

    private fun lineOf(saleId: Long): Long = world.tables.lines.values.single {
        it.saleId == saleId
    }.id

    private fun balance(): Money = world.tables.moneyAccounts.getValue(ACCOUNT).balance

    private suspend fun exchange(
        saleId: Long,
        replacement: List<SaleLineRequest>,
        returned: Long = 1_000,
    ): ExchangeResult = world.exchangeSale()(
        ExchangeRequest(
            saleId = saleId,
            lines = listOf(ReturnLineRequest(lineOf(saleId), QuantityMilli(returned))),
            replacement = SaleRequest(replacement, SaleWorld.CASH),
            refundAccountId = ACCOUNT,
        ),
    )

    @Test
    fun aDearerProductLeavesOnlyTheDifferenceInTheAccount() = runTest {
        val saleId = soapSale()
        val before = balance()

        val result = exchange(saleId, listOf(SaleLineRequest(RICE, QuantityMilli(1_000))))

        val exchanged = result as ExchangeResult.Exchanged
        assertEquals(before + Money(1_000), balance())
        val header = world.tables.saleReturns.getValue(exchanged.saleReturnId).content
        assertEquals(ReturnResolution.EXCHANGE, header.resolution)
        assertEquals(exchanged.replacementSaleId, header.replacementSaleId)
        assertEquals(Money(2_000), header.totalRefund)
        val types = world.tables.moneyMovements.values.sortedBy {
            it.id
        }.takeLast(2).map { it.type }
        assertEquals(listOf(MoneyMovementType.SALE_RECEIPT, MoneyMovementType.REFUND), types)
        assertEquals(QuantityMilli(4_000), world.tables.products.getValue(SOAP).onHand)
        assertEquals(QuantityMilli(4_000), world.tables.products.getValue(RICE).onHand)
        val back = world.tables.movements.values.single {
            it.movement.type == StockMovementType.SALE_RETURN
        }
        assertEquals(SOAP, back.movement.key.productId)
    }

    @Test
    fun aCheaperProductGivesTheDifferenceBack() = runTest {
        val saleId = soapSale()
        val before = balance()

        exchange(
            saleId,
            listOf(SaleLineRequest(RICE, QuantityMilli(1_000), unitPrice = Money(1_500))),
        )

        assertEquals(before - Money(500), balance())
    }

    @Test
    fun aRejectedReplacementLeavesNothing() = runTest {
        val saleId = soapSale()
        val before = world.tables

        assertEquals(
            ExchangeResult.SaleRejected(RegisterSaleResult.EmptySale),
            exchange(saleId, emptyList()),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun aRejectedReturnAlsoDropsTheReplacement() = runTest {
        val saleId = soapSale()
        val before = world.tables

        val result = exchange(saleId, listOf(SaleLineRequest(RICE, QuantityMilli(1_000))), 3_000)

        assertEquals(
            ExchangeResult.ReturnRejected(
                RegisterSaleReturnResult.AboveRemaining(0, QuantityMilli(2_000)),
            ),
            result,
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun aFailureInAnyWriteLeavesNeitherSaleNorReturn() = runTest {
        val saleId = soapSale()
        val before = world.tables
        var failures = 0
        for (failAt in 1..60) {
            world.failAtWrite = failAt
            val attempt = runCatching {
                exchange(saleId, listOf(SaleLineRequest(RICE, QuantityMilli(1_000))))
            }
            if (attempt.isSuccess) {
                assertTrue(attempt.getOrThrow() is ExchangeResult.Exchanged)
                break
            }
            assertTrue("Escritura $failAt", attempt.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
            failures++
        }
        assertTrue("$failures", failures > 10)
    }

    @Test
    fun aStorageFailureComesBackAsAResult() = runTest {
        val saleId = soapSale()
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            ExchangeResult.StorageFailed(StorageFailure.DISK_FULL),
            exchange(saleId, listOf(SaleLineRequest(RICE, QuantityMilli(1_000)))),
        )
    }

    private companion object {
        const val ACCOUNT = 10L
        const val SOAP = 1L
        const val RICE = 2L
    }
}
