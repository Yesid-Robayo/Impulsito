package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodAnalysis
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitLeader
import io.github.yesidrobayo.shopledger.domain.model.report.VariantOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los productos que dejan el 80 % de la ganancia, con los valores de la regla escritos a mano. */
class ProfitConcentrationRulesTest {
    @Test
    fun `the example of the rule leaves the first two at 8000 basis points`() {
        // Ganancias de 5000, 3000, 1000, 500 y 500: los dos primeros suman 8000 de 10000.
        val products =
            listOf(
                sales(3, profit = 1_000),
                sales(1, profit = 5_000),
                sales(5, profit = 500),
                sales(2, profit = 3_000),
                sales(4, profit = 500),
            )

        assertEquals(
            ProfitConcentration.Leaders(
                leaders =
                    listOf(
                        ProfitLeader(1, "Producto 1", Money(5_000), BasisPoints(5_000)),
                        ProfitLeader(2, "Producto 2", Money(3_000), BasisPoints(3_000)),
                    ),
                soldProducts = 5,
                reachedShare = BasisPoints(8_000),
            ),
            ProfitConcentrationRules.of(products),
        )
    }

    @Test
    fun `a product enters while what came before it is still under 80 percent`() {
        // 7999 antes del segundo: no llega al 80 % de 10000, así que el segundo entra. Con él se
        // llega justo a 8000, y el tercero ya no.
        val products =
            listOf(sales(1, profit = 7_999), sales(2, profit = 1), sales(3, profit = 2_000))

        val leaders = ProfitConcentrationRules.of(products) as ProfitConcentration.Leaders

        assertEquals(listOf(1L, 2L), leaders.leaders.map { it.productId })
        assertEquals(BasisPoints(8_000), leaders.reachedShare)
    }

    @Test
    fun `ties go by id and a loss never leads`() {
        // Total 10000 - 2000 = 8000. 4000 antes del segundo: 50 %, entra.
        val products =
            listOf(sales(9, profit = 5_000), sales(4, profit = 5_000), sales(7, profit = -2_000))

        val leaders = ProfitConcentrationRules.of(products) as ProfitConcentration.Leaders

        assertEquals(listOf(4L, 9L), leaders.leaders.map { it.productId })
        // 5000 de 8000 son 6250 puntos, y los dos juntos 12500.
        assertEquals(BasisPoints(6_250), leaders.leaders.first().share)
        assertEquals(BasisPoints(12_500), leaders.reachedShare)
        assertEquals(3, leaders.soldProducts)
    }

    @Test
    fun `variants count in their main product with its name`() {
        val products =
            listOf(
                variant(11, profit = 3_000),
                variant(12, profit = 3_000),
                // Lo que el principal vendió antes de tener variantes también es suyo.
                sales(SHIRT, profit = 1_000).copy(name = "Camiseta"),
                sales(2, profit = 3_000),
            )

        val leaders = ProfitConcentrationRules.of(products) as ProfitConcentration.Leaders

        assertEquals(
            ProfitLeader(SHIRT, "Camiseta", Money(7_000), BasisPoints(7_000)),
            leaders.leaders.first(),
        )
        assertEquals(2, leaders.soldProducts)
    }

    @Test
    fun `without sales there is nobody to order`() {
        assertEquals(ProfitConcentration.NoSales, ProfitConcentrationRules.of(emptyList()))
    }

    @Test
    fun `without profit there is no 80 percent to split`() {
        assertEquals(
            ProfitConcentration.NoProfit,
            ProfitConcentrationRules.of(listOf(sales(1, profit = 0))),
        )
        assertEquals(
            ProfitConcentration.NoProfit,
            ProfitConcentrationRules.of(listOf(sales(1, profit = 500), sales(2, profit = -900))),
        )
    }

    @Test
    fun `an absurd figure gives no list instead of a wrong one`() {
        val half = Long.MAX_VALUE / 2 + 1
        // Las ganancias no caben sumadas.
        assertEquals(
            ProfitConcentration.OutOfRange,
            ProfitConcentrationRules.of(listOf(sales(1, profit = half), sales(2, profit = half))),
        )
        // Una sola fila de un producto que no cabe al restar el costo.
        assertEquals(
            ProfitConcentration.OutOfRange,
            ProfitConcentrationRules.of(
                listOf(
                    sales(1, profit = 0).copy(netSales = Money(Long.MIN_VALUE), cost = Money(1)),
                ),
            ),
        )
        // La parte del primero no cabe: deja casi todo y casi todo se pierde en otro.
        val huge = Long.MAX_VALUE / 2
        assertEquals(
            ProfitConcentration.OutOfRange,
            ProfitConcentrationRules.of(
                listOf(sales(1, profit = huge), sales(2, profit = 1 - huge)),
            ),
        )
        // Lo acumulado por cinco no cabe al comparar con el corte.
        val big = Long.MAX_VALUE / 4
        assertEquals(
            ProfitConcentration.OutOfRange,
            ProfitConcentrationRules.of(
                listOf(sales(1, profit = big), sales(2, profit = big), sales(3, profit = -big)),
            ),
        )
    }

    @Test
    fun `the analysis is empty only when every part is`() {
        val idle = IdleProducts(
            listOf(IdleProduct(1, "Té", 1, QuantityMilli(1_000), Money(500))),
            1,
        )
        val buyer = CustomerSales(1, null, 1, Money(100))
        val none = PeriodAnalysis(ProfitConcentration.NoSales, IdleProducts.NONE, emptyList())

        assertTrue(none.isEmpty)
        assertFalse(none.copy(concentration = ProfitConcentration.NoProfit).isEmpty)
        assertFalse(none.copy(idle = idle).isEmpty)
        assertFalse(none.copy(customers = listOf(buyer)).isEmpty)
    }

    private fun sales(id: Long, profit: Long) =
        ProductSales(
            id,
            "Producto $id",
            1,
            QuantityMilli(1_000),
            Money(profit),
            Money.ZERO,
        )

    private fun variant(id: Long, profit: Long) = sales(id, profit).copy(
        name = "Camiseta · $id",
        variantOf = VariantOf(SHIRT, "Camiseta", VariantValues("Talla $id", null)),
    )

    private companion object {
        const val SHIRT = 10L
    }
}
