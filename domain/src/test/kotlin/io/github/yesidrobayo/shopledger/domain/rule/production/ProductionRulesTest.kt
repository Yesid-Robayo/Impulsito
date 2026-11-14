package io.github.yesidrobayo.shopledger.domain.rule.production

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.product
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.row
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LeafStockPlan
import org.junit.Assert.assertEquals
import org.junit.Test

// Los ids y las cifras son inventados: 1 es lo que se produce, 10 a 19 insumos y 20 un combo.
class ProductionRulesTest {
    private val candle = 1L
    private val wax = 10L
    private val wick = 11L
    private val label = 12L
    private val kit = 20L

    private val empty = CostState(QuantityMilli.ZERO, Money.ZERO, Money.ZERO)

    // El ejemplo de la regla de producción: cera con 5 kg que valen 40000 y mechas con 100 que
    // valen 15000.
    private val waxStock = product(wax, onHand = 5_000, averageCost = 8_000, stockValue = 40_000)
    private val wickStock =
        product(wick, onHand = 100_000, averageCost = 150, stockValue = 15_000)

    @Test
    fun `the recipe asks for each input times the quantity`() {
        val recipes = recipesOf(row(candle, wax, 200), row(candle, wick, 1_000))

        val result = ProductionRules.recipeInputs(candle, units(10), recipes, emptyMap())

        assertEquals(
            RecipeInputsResult.Inputs(
                listOf(input(wax, 2_000), input(wick, 10_000)),
            ),
            result,
        )
    }

    @Test
    fun `an input reached twice is one row with the sum, where it appeared first`() {
        val recipes = recipesOf(
            row(candle, wax, 200),
            row(candle, kit, 1_000),
            row(kit, wick, 1_000),
            row(kit, wax, 100),
        )
        val modes = mapOf(kit to ProductStockMode.COMPONENTS)

        val result = ProductionRules.recipeInputs(candle, units(10), recipes, modes)

        assertEquals(
            RecipeInputsResult.Inputs(listOf(input(wax, 3_000), input(wick, 10_000))),
            result,
        )
    }

    @Test
    fun `an input that rounds to zero is named`() {
        val recipes = recipesOf(row(candle, wax, 1))

        val result = ProductionRules.recipeInputs(candle, QuantityMilli(1), recipes, emptyMap())

        assertEquals(RecipeInputsResult.InputRoundsToZero(wax), result)
    }

    @Test
    fun `an input over the line limit is out of range`() {
        val recipes = recipesOf(row(candle, wax, 2_000))

        val result = ProductionRules.recipeInputs(
            candle,
            QuantityMilli(Limit.LINE_QUANTITY_MILLI.max),
            recipes,
            emptyMap(),
        )

        assertEquals(RecipeInputsResult.QuantityOutOfRange, result)
    }

    @Test
    fun `two paths that add up over the line limit are out of range`() {
        val recipes = recipesOf(
            row(candle, wax, 1_000),
            row(candle, kit, 1_000),
            row(kit, wax, 1_000),
        )
        val modes = mapOf(kit to ProductStockMode.COMPONENTS)

        val result = ProductionRules.recipeInputs(
            candle,
            QuantityMilli(Limit.LINE_QUANTITY_MILLI.max),
            recipes,
            modes,
        )

        assertEquals(RecipeInputsResult.QuantityOutOfRange, result)
    }

    @Test
    fun `a third level of combos is too deep`() {
        val inner = 21L
        val recipes = recipesOf(row(candle, kit, 1_000), row(kit, inner, 1_000))
        val modes =
            mapOf(kit to ProductStockMode.COMPONENTS, inner to ProductStockMode.COMPONENTS)

        val result = ProductionRules.recipeInputs(candle, units(1), recipes, modes)

        assertEquals(RecipeInputsResult.TooDeep, result)
    }

    @Test
    fun `the example of the rule leaves the candles at the sum of the inputs`() {
        val plan = planned(
            ProductionRules.plan(
                empty,
                units(10),
                listOf(input(wax, 2_000), input(wick, 10_000)),
                catalog(waxStock, wickStock),
            ),
        )

        assertEquals(Money(17_500), plan.totalCost)
        assertEquals(Money(1_750), plan.unitCost)
        assertEquals(
            CostState(QuantityMilli(10_000), Money(1_750), Money(17_500)),
            plan.outputAfter,
        )
        assertEquals(empty, plan.outputBefore)
        val (waxExit, wickExit) = plan.inputs.map { it as LeafStockPlan.Tracked }
        assertEquals(Money(16_000), waxExit.exit.cost)
        assertEquals(Money(1_500), wickExit.exit.cost)
        assertEquals(
            CostState(QuantityMilli(3_000), Money(8_000), Money(24_000)),
            plan.inputStatesAfter[wax],
        )
        assertEquals(
            CostState(QuantityMilli(90_000), Money(150), Money(13_500)),
            plan.inputStatesAfter[wick],
        )
    }

    @Test
    fun `an input with lots leaves by expiry or from its chosen lot, one part per lot`() {
        fun lot(id: Long, expiry: String?, milli: Long) = LotStock(
            lotId = id,
            code = if (expiry == null) null else "L$id",
            expiryDate = expiry?.let { requireNotNull(BusinessDate.parse(it)) },
            isUnassigned = expiry == null,
            onHand = QuantityMilli(milli),
        )
        val lots = mapOf(
            wax to listOf(
                lot(102, "2026-11-20", 4_000),
                lot(101, "2026-11-10", 1_000),
                lot(103, null, 0),
            ),
        )
        val service = product(label, ProductStockMode.UNTRACKED, fixedUnitCost = 300)
        val products = catalog(waxStock, wickStock, service)

        val byExpiry = planned(
            ProductionRules.plan(empty, units(10), listOf(input(wax, 3_000)), products, lots),
        )
        val chosen = planned(
            ProductionRules.plan(
                empty,
                units(10),
                listOf(
                    ProductionInput(wax, QuantityMilli(2_000), lotId = 102),
                    input(wick, 10_000),
                    input(label, 10_000),
                ),
                products,
                lots,
            ),
        )

        val parts = (byExpiry.inputs.single() as LeafStockPlan.Tracked).exit.lots
        val split = parts.map { it.lotId to it.quantity.milli }
        assertEquals(listOf(101L to 1_000L, 102L to 2_000L), split)
        assertEquals(Money(24_000), byExpiry.totalCost)
        val (waxExit, wickExit) = chosen.inputs.take(2).map { it as LeafStockPlan.Tracked }
        assertEquals(listOf(102L), waxExit.exit.lots.map { it.lotId })
        assertEquals(Money(16_000), waxExit.exit.cost)
        // Un insumo sin lotes leídos sale en una sola parte, y uno sin stock no sale.
        assertEquals(emptyList<Any>(), wickExit.exit.lots)
        assertEquals(Money(20_500), chosen.totalCost)
    }

    @Test
    fun `a missing input leaves its stock negative and still produces`() {
        val short = product(wax, onHand = 1_000, averageCost = 8_000, stockValue = 8_000)

        val plan = planned(
            ProductionRules.plan(empty, units(10), listOf(input(wax, 2_000)), catalog(short)),
        )

        // Sale 1 kg con su valor, 8000, y el kilo que falta al promedio, otros 8000.
        assertEquals(Money(16_000), plan.totalCost)
        assertEquals(QuantityMilli(-1_000), plan.inputStatesAfter.getValue(wax).onHand)
    }

    @Test
    fun `an input without stock adds its fixed cost and moves nothing`() {
        val service = product(label, ProductStockMode.UNTRACKED, fixedUnitCost = 300)

        val plan = planned(
            ProductionRules.plan(
                empty,
                units(10),
                listOf(input(wax, 2_000), input(label, 10_000)),
                catalog(waxStock, service),
            ),
        )

        assertEquals(Money(19_000), plan.totalCost)
        assertEquals(
            LeafStockPlan.Untracked(label, QuantityMilli(10_000), Money(300), Money(3_000)),
            plan.inputs[1],
        )
        assertEquals(setOf(wax), plan.inputStatesAfter.keys)
    }

    @Test
    fun `production on top of stock weighs the old value and the new one`() {
        val before = CostState(QuantityMilli(10_000), Money(1_500), Money(15_000))

        val plan = planned(
            ProductionRules.plan(
                before,
                units(10),
                listOf(input(wax, 2_000), input(wick, 10_000)),
                catalog(waxStock, wickStock),
            ),
        )

        assertEquals(
            CostState(QuantityMilli(20_000), Money(1_625), Money(32_500)),
            plan.outputAfter,
        )
    }

    @Test
    fun `an input cost that does not fit is an overflow`() {
        val broken = product(wax, onHand = 1, averageCost = 1, stockValue = Long.MAX_VALUE)

        val result =
            ProductionRules.plan(empty, units(1), listOf(input(wax, 2_000)), catalog(broken))

        assertEquals(Checked.Overflow, result)
    }

    @Test
    fun `an input cost over a document total is above that limit`() {
        val huge = product(wax, onHand = 1_000, stockValue = 2_000_000_000_000_000L)

        val result =
            ProductionRules.plan(empty, units(1), listOf(input(wax, 1_000)), catalog(huge))

        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), result)
    }

    @Test
    fun `a unit cost over the unit limit is rejected`() {
        val result = ProductionRules.plan(
            empty,
            QuantityMilli(1),
            listOf(input(wax, 2_000)),
            catalog(waxStock),
        )

        // 16000 por una milésima son 16 millones por unidad: cabe. El tope llega con más valor.
        assertEquals(Money(16_000_000), planned(result).unitCost)
        val rich = product(wax, onHand = 1_000, stockValue = 2_000_000_000L)
        assertEquals(
            Checked.AboveLimit(Limit.UNIT_MONEY),
            ProductionRules.plan(
                empty,
                QuantityMilli(1),
                listOf(input(wax, 1_000)),
                catalog(rich),
            ),
        )
    }

    @Test
    fun `an entry that does not fit is an overflow`() {
        val full = CostState(QuantityMilli(1_000), Money(1), Money(Long.MAX_VALUE - 1))

        val result =
            ProductionRules.plan(full, units(1), listOf(input(wax, 1_000)), catalog(waxStock))

        assertEquals(Checked.Overflow, result)
    }

    @Test
    fun `the produced stock over its limit is rejected`() {
        val full = CostState(QuantityMilli(Limit.ON_HAND_MILLI.max), Money.ZERO, Money.ZERO)

        val result =
            ProductionRules.plan(full, units(1), listOf(input(wax, 1_000)), catalog(waxStock))

        assertEquals(Checked.AboveLimit(Limit.ON_HAND_MILLI), result)
    }

    @Test
    fun `the produced stock value over its limit is rejected`() {
        val full =
            CostState(QuantityMilli(1_000), Money(1), Money(Limit.STOCK_VALUE.max))

        val result =
            ProductionRules.plan(full, units(1), listOf(input(wax, 1_000)), catalog(waxStock))

        assertEquals(Checked.AboveLimit(Limit.STOCK_VALUE), result)
    }

    @Test
    fun `an average cost over the unit limit is rejected`() {
        // Un estado que la app no escribe, con un promedio ya sobre el tope.
        val odd = CostState(QuantityMilli(1), Money(1), Money(2_000_000_000L))

        val result =
            ProductionRules.plan(odd, QuantityMilli(1), listOf(input(wax, 1)), catalog(waxStock))

        assertEquals(Checked.AboveLimit(Limit.UNIT_MONEY), result)
    }

    private fun planned(result: Checked<ProductionPlan>): ProductionPlan =
        (result as Checked.Value).value

    private fun recipesOf(vararg rows: RecipeComponent): Map<Long, List<RecipeComponent>> =
        rows.groupBy { it.parentProductId }

    private fun catalog(vararg products: Product): Map<Long, Product> =
        products.associateBy { it.id }

    private fun input(productId: Long, milli: Long) =
        ProductionInput(productId, QuantityMilli(milli))

    private fun units(count: Long) = QuantityMilli(count * 1_000)
}
