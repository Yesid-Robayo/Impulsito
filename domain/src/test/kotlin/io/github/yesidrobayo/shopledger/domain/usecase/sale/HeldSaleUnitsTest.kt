package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FakeHeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Una venta en espera con una línea por caja: guarda la unidad, convierte un porcentaje con el
 * precio de la caja, y al retomarla vuelve en la misma unidad. Montos inventados.
 */
class HeldSaleUnitsTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS)
    private val products = FakeProductRepository(database)
    private val units = FakeProductUnitRepository(database)
    private val heldSales = FakeHeldSaleRepository(database)
    private val box = 3L

    init {
        database.seed { it.copy(products = mapOf(1L to product(1, salePrice = 500))) }
    }

    @Test
    fun aLineByTheBoxIsHeldAndResumedByTheBox() = runTest {
        transactions.write {
            units.insert(1, ConversionDetails(box, 24_000, null, null, true, false), clock.now())
            TxResult.Commit(Unit)
        }
        val line = SaleLineRequest(
            1,
            QuantityMilli(2_000),
            discount = LineDiscount.Percent(BasisPoints(1_000)),
            unitId = box,
        )

        val held = HoldSaleUseCase(transactions, heldSales, products, clock, units)(
            HoldSaleRequest(listOf(line), "Mesa 4"),
        )

        val id = (held as HoldSaleResult.Held).heldSaleId
        val stored = database.tables.value.heldSales.getValue(id).lines.single()
        assertEquals(box, stored.unitId)
        // El 10 % de 2 cajas a 12000 es 2400.
        assertEquals(Money(2_400), stored.discount)
        val resumed = ResumeHeldSaleUseCase(
            transactions,
            heldSales,
            products,
            FakePriceHistoryRepository(database),
        )(id) as ResumeHeldSaleResult.Resumed
        assertEquals(
            listOf(line.copy(discount = LineDiscount.Amount(Money(2_400)))),
            resumed.sale.lines,
        )
    }

    @Test
    fun withoutTheConversionAPercentHasNothingToTakeFrom() = runTest {
        val line = SaleLineRequest(
            1,
            QuantityMilli(1_000),
            discount = LineDiscount.Percent(BasisPoints(1_000)),
            unitId = box,
        )

        val held = HoldSaleUseCase(transactions, heldSales, products, clock, units)(
            HoldSaleRequest(listOf(line), null),
        )

        val id = (held as HoldSaleResult.Held).heldSaleId
        assertEquals(
            Money.ZERO,
            database.tables.value.heldSales.getValue(id).lines.single().discount,
        )
    }
}
