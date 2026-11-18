package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseWorld
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentsResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La tarjeta con los casos de uso de siempre, no con los de mentira: lo que se registra es lo que
 * muestra la tarjeta, en una sola transacción, y una falla de la base no deja nada.
 */
class RegisterPhraseWithUseCasesTest {
    /** Solo el caso de uso que se prueba: cualquier otro sería un error de la tarjeta. */
    private fun writers(
        sale: suspend (SaleRequest) -> RegisterSaleResult = { error("No es una venta") },
        expense: suspend (ExpenseRequest) -> RegisterExpenseResult = { error("No es un gasto") },
        adjustments: suspend (List<StockAdjustmentRequest>) -> StockAdjustmentsResult = {
            error("No es un ajuste")
        },
    ) = PhraseWriters(
        sale = sale,
        purchase = { error("No es una compra") },
        expense = expense,
        production = { error("No es una producción") },
        adjustments = adjustments,
        customerPayment = { error("No es un abono") },
        supplierPayment = { error("No es un pago") },
        cost = { _, _ -> error("No es un costo") },
        price = { _, _ -> error("No es un precio") },
        product = { _, _ -> error("No es un producto") },
        recipe = { _, _ -> error("No es una receta") },
    )

    // Gasto.

    private val expenses = ExpenseWorld()

    private fun registerExpense(): RegisterPhraseUseCase {
        val database = expenses.database
        return RegisterPhraseUseCase(
            writers = writers(expense = { expenses.register()(it) }),
            products = FakeProductRepository(database),
            productUnits = FakeProductUnitRepository(database),
            units = FakeUnitRepository(database),
            components = FakeProductComponentRepository(database),
            paymentMethods = FakePaymentMethodRepository(database),
        )
    }

    private fun expenseDraft() = PhraseDraft(
        intent = PhraseIntent.EXPENSE,
        categoryId = expenses.seeded.getValue("RENT"),
        total = Money(80_000),
    )

    @Test
    fun `an expense is registered once, with the amount on the card`() = runTest {
        val result = registerExpense()(expenseDraft())
        val stored = expenses.database.read().expenses.values.single()
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.EXPENSE, stored.id), result)
        assertEquals(Money(80_000), stored.content.amount)
        assertEquals(expenses.seeded.getValue("RENT"), stored.content.expenseCategoryId)
        assertEquals(1, expenses.transactions.commits)
    }

    @Test
    fun `an expense that cannot be written leaves nothing`() = runTest {
        expenses.transactions.restoreInProgress = true
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            registerExpense()(expenseDraft()),
        )
        assertTrue(expenses.database.read().expenses.isEmpty())
    }

    // Conteo de varios productos.

    private val stock = StockFixture()

    private fun registerCount(): RegisterPhraseUseCase {
        val catalog = stock.catalog
        return RegisterPhraseUseCase(
            writers = writers(adjustments = { stock.adjust.all(it) }),
            products = catalog.products,
            productUnits = FakeProductUnitRepository(catalog.database),
            units = catalog.units,
            components = catalog.components,
            paymentMethods = FakePaymentMethodRepository(catalog.database),
        )
    }

    private fun countDraft(vararg counted: Pair<Long, Long>) = PhraseDraft(
        intent = PhraseIntent.STOCK_ADJUSTMENT,
        reason = StockAdjustmentReason.COUNT_CORRECTION,
        lines = counted.map { (id, milli) ->
            DraftLine(DraftTarget.Existing(id), QuantityMilli(milli), unitId = null)
        },
    )

    @Test
    fun `a count of three products is registered whole in one transaction`() = runTest {
        stock.seedStock(id = 1L, units = 4_000L, unitCost = 1_000L)
        stock.seedStock(id = 2L, units = 0L, unitCost = 500L)
        stock.seedStock(id = 3L, units = 2_000L, unitCost = 800L)
        val commits = stock.catalog.transactions.commits

        val result = registerCount()(countDraft(1L to 1_000L, 2L to 1_000L, 3L to 1_000L))

        val first = stock.movementsOf(1L).last()
        assertEquals(
            RegisterPhraseResult.Registered(PhraseIntent.STOCK_ADJUSTMENT, first.id),
            result,
        )
        assertEquals(commits + 1, stock.catalog.transactions.commits)
        for (id in 1L..3L) {
            assertEquals(QuantityMilli(1_000L), stock.product(id).onHand)
            assertEquals(
                StockAdjustmentReason.COUNT_CORRECTION,
                stock.movementsOf(id).last().reason,
            )
        }
    }

    @Test
    fun `a count whose second line fails leaves nothing`() = runTest {
        stock.seedStock(id = 1L, units = 4_000L, unitCost = 1_000L)
        stock.seedStock(id = 2L, units = 2_000L, unitCost = 800L)
        // Cuántas escrituras hace la primera línea sola, para fallar en la primera de la segunda.
        val counting = StockFixture()
        counting.seedStock(id = 1L, units = 4_000L, unitCost = 1_000L)
        counting.adjust.all(listOf(StockAdjustmentRequest.Count(1L, QuantityMilli(1_000L))))
        val before = stock.catalog.tables
        stock.catalog.database.failAtWrite = counting.catalog.database.lastTransactionWrites + 1
        stock.catalog.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = registerCount()(countDraft(1L to 1_000L, 2L to 1_000L))

        assertEquals(RegisterPhraseResult.StorageFailed(StorageFailure.DISK_FULL), result)
        assertEquals(before, stock.catalog.tables)
    }

    // Venta.

    private val sales = SaleWorld().apply {
        addProduct(product(BREAD, salePrice = 500))
        addProduct(product(SODA, salePrice = 300))
        addMethod(method())
    }

    private fun registerSale() = RegisterPhraseUseCase(
        writers = writers(sale = { sales.registerSale()(it) }),
        products = sales.products,
        productUnits = sales.productUnitRepository,
        units = sales.unitRepository,
        components = sales.recipeRepository,
        paymentMethods = FakePaymentMethodRepository(InMemoryDatabase()),
    )

    private fun line(productId: Long, quantity: Long, lineTotal: Long? = null) = DraftLine(
        product = DraftTarget.Existing(productId),
        quantity = QuantityMilli(quantity),
        unitId = null,
        lineTotal = lineTotal?.let(::Money),
    )

    private fun saleDraft(vararg lines: DraftLine, total: Long? = null) = PhraseDraft(
        intent = PhraseIntent.SALE,
        lines = lines.toList(),
        paymentMethodId = SaleWorld.CASH,
        total = total?.let(::Money),
    )

    @Test
    fun `three breads for 1000 are a sale of exactly 1000`() = runTest {
        val result = registerSale()(saleDraft(line(BREAD, 3_000, lineTotal = 1_000)))
        val sale = sales.tables.sales.values.single()
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.SALE, sale.id), result)
        assertEquals(Money(1_000), sale.content.total)
        assertEquals(1, sales.commits)
    }

    @Test
    fun `a total for the whole sale is what the sale charges`() = runTest {
        // 2 panes a 500 y una gaseosa a 300 son 1300: todo por 1200.
        registerSale()(saleDraft(line(BREAD, 2_000), line(SODA, 1_000), total = 1_200))
        val sale = sales.tables.sales.values.single()
        assertEquals(Money(1_200), sale.content.total)
        assertEquals(Money(-100), sale.content.orderAdjustment)
    }

    @Test
    fun `a sale whose write fails leaves nothing`() = runTest {
        sales.failAtWrite = 1
        sales.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.DISK_FULL),
            registerSale()(saleDraft(line(BREAD, 1_000))),
        )
        assertTrue(sales.tables.sales.isEmpty())
        assertTrue(sales.tables.movements.isEmpty())
    }

    private companion object {
        const val BREAD = 10L
        const val SODA = 11L
    }
}
