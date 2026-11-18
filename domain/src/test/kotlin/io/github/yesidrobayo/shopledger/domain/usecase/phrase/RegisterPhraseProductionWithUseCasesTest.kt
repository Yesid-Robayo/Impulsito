package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.CANDLE
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.LABEL
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WAX
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WICK
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.measureUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Producir por frase con los casos de uso de siempre, sobre el catálogo en memoria: la receta que
 * se dijo y la producción quedan juntas en una sola transacción, y varias producciones también. Si
 * una se rechaza o la base falla en cualquier escritura, no queda nada.
 *
 * Las velas del ejemplo de la regla de producción: 5 kg de cera por 40000 y 100 mechas por 15000,
 * y una vela lleva 0,200 kg de cera y una mecha. Los costos esperados están calculados a mano.
 */
class RegisterPhraseProductionWithUseCasesTest {
    private suspend fun prepared(): ProductionFixture = ProductionFixture().apply {
        seedCandles()
        catalog.seedProduct(SOAP, "Jabón inventado")
        catalog.database.seed { tables ->
            val gram = measureUnit(GRAM, "GRAM", UnitKind.MASS, allowsFraction = true)
            tables.copy(units = tables.units + (GRAM to gram))
        }
        stockCandleInputs()
    }

    private fun registerOf(fixture: ProductionFixture): RegisterPhraseUseCase {
        val catalog = fixture.catalog
        val productions = RegisterProductionsUseCase(
            catalog.transactions,
            SaveRecipeUseCase(
                catalog.transactions,
                catalog.products,
                catalog.components,
                catalog.clock,
            ),
            fixture.register,
        )
        return RegisterPhraseUseCase(
            writers = PhraseWriters(
                sale = { error("No es una venta") },
                purchase = { error("No es una compra") },
                expense = { error("No es un gasto") },
                productions = { productions(it) },
                adjustments = { error("No es un ajuste") },
                customerPayment = { error("No es un abono") },
                supplierPayment = { error("No es un pago") },
                cost = { _, _ -> error("No es un costo") },
                price = { _, _ -> error("No es un precio") },
                product = { _, _ -> error("No es un producto") },
                recipe = { _, _ -> error("No es una receta") },
            ),
            products = catalog.products,
            productUnits = FakeProductUnitRepository(catalog.database),
            units = catalog.units,
            components = catalog.components,
            paymentMethods = FakePaymentMethodRepository(catalog.database),
        )
    }

    private fun line(productId: Long, milli: Long, unitId: Long? = null) =
        DraftLine(DraftTarget.Existing(productId), QuantityMilli(milli), unitId)

    private fun made(
        vararg lines: DraftLine,
        inputs: List<DraftLine> = emptyList(),
        updatesRecipe: Boolean = false,
    ) = PhraseDraft(
        intent = PhraseIntent.PRODUCTION,
        lines = lines.toList(),
        inputs = inputs,
        updatesRecipe = updatesRecipe,
    )

    /** "hice 2 jabones con 400 g de cera y 2 mechas", de un jabón sin receta. */
    private val soapsWithoutRecipe = made(
        line(SOAP, 2_000),
        inputs = listOf(line(WAX, 400_000, GRAM), line(WICK, 2_000)),
    )

    private fun recipeOf(fixture: ProductionFixture, parentId: Long): List<Pair<Long, Long>> =
        fixture.catalog.tables.productComponents.values
            .filter { it.parentProductId == parentId }
            .sortedBy { it.id }
            .map { it.componentProductId to it.quantity.milli }

    @Test
    fun `a product without a recipe is produced with what was said and keeps it as recipe`() =
        runTest {
            val fixture = prepared()
            val catalog = fixture.catalog
            val commits = catalog.transactions.commits

            val result = registerOf(fixture)(soapsWithoutRecipe)

            assertEquals(RegisterPhraseResult.Registered(PhraseIntent.PRODUCTION, 1L), result)
            // Cera: 0,400 kg a 8000 son 3200. Mechas: 2 a 150 son 300. El jabón entra por 3500,
            // a 1750 cada uno.
            val soap = catalog.product(SOAP)
            assertEquals(QuantityMilli(2_000), soap.onHand)
            assertEquals(Money(3_500), soap.stockValue)
            assertEquals(Money(1_750), soap.averageCost)
            assertEquals(QuantityMilli(4_600), catalog.product(WAX).onHand)
            assertEquals(Money(36_800), catalog.product(WAX).stockValue)
            assertEquals(QuantityMilli(98_000), catalog.product(WICK).onHand)
            assertEquals(Money(14_700), catalog.product(WICK).stockValue)
            // La receta guarda lo de un jabón: 0,200 kg de cera y una mecha.
            assertEquals(listOf(WAX to 200L, WICK to 1_000L), recipeOf(fixture, SOAP))
            assertEquals(commits + 1, catalog.transactions.commits)
        }

    @Test
    fun `what was said differs from the recipe and is used only in this batch`() = runTest {
        val fixture = prepared()
        val catalog = fixture.catalog

        // "hice 10 velas con 2,5 kg de cera": la receta pide 2 kg; las mechas salen de ella.
        registerOf(fixture)(made(line(CANDLE, 10_000), inputs = listOf(line(WAX, 2_500))))

        // Cera: 2,5 kg a 8000 son 20000. Mechas: 10 a 150 son 1500. Las velas entran por 21500.
        val candle = catalog.product(CANDLE)
        assertEquals(Money(21_500), candle.stockValue)
        assertEquals(Money(2_150), candle.averageCost)
        assertEquals(QuantityMilli(2_500), catalog.product(WAX).onHand)
        assertEquals(Money(20_000), catalog.product(WAX).stockValue)
        assertEquals(QuantityMilli(90_000), catalog.product(WICK).onHand)
        assertEquals(listOf(WAX to 200L, WICK to 1_000L), recipeOf(fixture, CANDLE))
    }

    @Test
    fun `with the switch on, the recipe also changes in the same transaction`() = runTest {
        val fixture = prepared()
        val catalog = fixture.catalog
        val commits = catalog.transactions.commits

        registerOf(fixture)(
            made(line(CANDLE, 10_000), inputs = listOf(line(WAX, 2_500)), updatesRecipe = true),
        )

        assertEquals(Money(21_500), catalog.product(CANDLE).stockValue)
        // 2,5 kg para 10 velas son 0,250 kg por vela; la mecha queda como estaba.
        assertEquals(listOf(WAX to 250L, WICK to 1_000L), recipeOf(fixture, CANDLE))
        assertEquals(commits + 1, catalog.transactions.commits)
    }

    @Test
    fun `several productions are registered together, each seeing the stock of the one before`() =
        runTest {
            val fixture = prepared()
            fixture.seedRecipe(SOAP, WAX to 100L)
            val catalog = fixture.catalog
            val commits = catalog.transactions.commits

            // "hice 10 velas y 4 jabones".
            val result = registerOf(fixture)(made(line(CANDLE, 10_000), line(SOAP, 4_000)))

            assertEquals(RegisterPhraseResult.Registered(PhraseIntent.PRODUCTION, 1L), result)
            // Velas: 2 kg de cera por 16000 y 10 mechas por 1500. Jabones: 0,400 kg de cera, al
            // promedio de 8000 que dejaron las velas, por 3200.
            assertEquals(Money(17_500), catalog.product(CANDLE).stockValue)
            assertEquals(Money(3_200), catalog.product(SOAP).stockValue)
            assertEquals(Money(800), catalog.product(SOAP).averageCost)
            assertEquals(QuantityMilli(2_600), catalog.product(WAX).onHand)
            assertEquals(Money(20_800), catalog.product(WAX).stockValue)
            assertEquals(2, catalog.tables.productionBatches.size)
            assertEquals(commits + 1, catalog.transactions.commits)
        }

    @Test
    fun `if one production is refused, none is left`() = runTest {
        val fixture = prepared()
        // Una etiqueta no lleva stock: no se produce, aunque tenga receta.
        fixture.seedRecipe(LABEL, WAX to 10L)
        val before = fixture.catalog.tables

        val result = registerOf(fixture)(made(line(CANDLE, 10_000), line(LABEL, 1_000)))

        assertEquals(
            RegisterPhraseResult.Rejected(
                PhraseRejection.Production(RegisterProductionResult.NotStocked),
            ),
            result,
        )
        assertEquals(before, fixture.catalog.tables)
    }

    @Test
    fun `a recipe that rounds to zero per unit is refused and nothing is produced`() = runTest {
        val fixture = prepared()
        val before = fixture.catalog.tables

        // 1 g de cera para 3 jabones es menos de media milésima de kilo por jabón.
        val result = registerOf(fixture)(
            made(line(SOAP, 3_000), inputs = listOf(line(WAX, 1_000, GRAM))),
        )

        val rejection = (result as RegisterPhraseResult.Rejected).reason as PhraseRejection.Recipe
        assertTrue(rejection.result is SaveRecipeResult.Invalid)
        assertEquals(before, fixture.catalog.tables)
    }

    @Test
    fun `a failure at each write of the recipe and the production leaves nothing`() = runTest {
        assertEachWriteLeavesNothing(soapsWithoutRecipe)
    }

    @Test
    fun `a failure at each write of several productions leaves nothing`() = runTest {
        assertEachWriteLeavesNothing(made(line(CANDLE, 10_000), line(CANDLE, 5_000)))
    }

    private suspend fun assertEachWriteLeavesNothing(draft: PhraseDraft) {
        val counting = prepared()
        registerOf(counting)(draft)
        val writes = counting.catalog.database.lastTransactionWrites
        assertTrue("Producir debe escribir algo", writes > 0)
        for (failAt in 1..writes) {
            val failing = prepared()
            val before = failing.catalog.tables
            failing.catalog.database.failAtWrite = failAt
            val failed = try {
                registerOf(failing)(draft)
                false
            } catch (expected: InjectedFailure) {
                true
            }
            assertTrue("La escritura $failAt debió fallar", failed)
            assertEquals("La escritura $failAt dejó algo", before, failing.catalog.tables)
        }
    }

    private companion object {
        const val SOAP = 5L

        /** Un id que no usa ninguna unidad del accesorio. */
        const val GRAM = 14L
    }
}
