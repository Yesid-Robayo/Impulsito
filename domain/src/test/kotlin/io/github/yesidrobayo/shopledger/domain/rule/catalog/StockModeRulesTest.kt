package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Montos en unidad menor de una moneda sin decimales y cantidades en milésimas. Cada estado de
// costo se escribe como (stock, costo promedio, valor del stock). Cada valor esperado está
// calculado a mano desde las reglas de costo, nunca con el código que se prueba.
class StockModeRulesTest {
    private val rules = StockModeRules

    @Test
    fun `each stock mode gives its line stock effect`() {
        assertEquals(LineStockEffect.DIRECT, rules.stockEffectOf(ProductStockMode.TRACKED))
        assertEquals(LineStockEffect.NONE, rules.stockEffectOf(ProductStockMode.UNTRACKED))
        assertEquals(LineStockEffect.COMPONENTS, rules.stockEffectOf(ProductStockMode.COMPONENTS))
    }

    @Test
    fun `only a tracked product has a ledger`() {
        assertTrue(rules.hasLedger(ProductStockMode.TRACKED))
        assertFalse(rules.hasLedger(ProductStockMode.UNTRACKED))
        assertFalse(rules.hasLedger(ProductStockMode.COMPONENTS))
    }

    @Test
    fun `a tracked product keeps the sum of its ledger even when it is negative`() {
        assertEquals(qty(-2000), rules.expectedOnHand(ProductStockMode.TRACKED, qty(-2000)))
        assertTrue(rules.onHandHolds(ProductStockMode.TRACKED, qty(-2000), qty(-2000)))
        assertTrue(rules.onHandHolds(ProductStockMode.TRACKED, qty(0), qty(0)))
        assertFalse(rules.onHandHolds(ProductStockMode.TRACKED, qty(3000), qty(2000)))
    }

    @Test
    fun `the on hand of an untracked or components product is always zero`() {
        for (mode in listOf(ProductStockMode.UNTRACKED, ProductStockMode.COMPONENTS)) {
            assertEquals(QuantityMilli.ZERO, rules.expectedOnHand(mode, qty(0)))
            assertTrue(rules.onHandHolds(mode, qty(0), qty(0)))
            // Un saldo guardado distinto de cero no cumple, aunque no haya filas: se repara a cero.
            assertFalse(rules.onHandHolds(mode, qty(5000), qty(0)))
            assertFalse(rules.onHandHolds(mode, qty(-1), qty(0)))
        }
    }

    @Test
    fun `no on hand fits a product without ledger whose rows do not add up to zero`() {
        for (mode in listOf(ProductStockMode.UNTRACKED, ProductStockMode.COMPONENTS)) {
            assertNull(rules.expectedOnHand(mode, qty(3000)))
            // Ni el saldo cero que pide el modo ni la suma que pide el libro.
            assertFalse(rules.onHandHolds(mode, qty(0), qty(3000)))
            assertFalse(rules.onHandHolds(mode, qty(3000), qty(3000)))
        }
    }

    @Test
    fun `a service of one and a half hours with a fixed cost of 2500 costs 3750`() {
        // Servicio de 8000 por hora, con costo fijo 2500: 1,500 horas.
        val plan = rules.planLine(LineStockSubject.Untracked(Money(2500), qty(1500))).accepted()

        assertEquals(LineStockPlan.NoStock(unitCost = Money(2500), lineCost = Money(3750)), plan)
        assertEquals(LineStockEffect.NONE, plan.stockEffect)
        // La venta de la misma línea: 8000 × 1,5 = 12000.
        assertEquals(Checked.Value(Money(12_000)), Money(8000).timesQuantity(qty(1500)))
    }

    @Test
    fun `an untracked line never writes a ledger row`() {
        // Solo una salida de un producto con stock o una hoja con stock tienen fila: un servicio
        // no tiene de dónde sacar una.
        val plan = rules.planLine(LineStockSubject.Untracked(Money(2500), qty(1500))).accepted()
        assertTrue(plan is LineStockPlan.NoStock)
        assertFalse(rules.hasLedger(LineStockSubject.Untracked(Money(1), qty(1)).stockMode))
    }

    @Test
    fun `a service without a fixed cost costs zero`() {
        val plan = rules.planLine(LineStockSubject.Untracked(Money.ZERO, qty(4000))).accepted()
        assertEquals(LineStockPlan.NoStock(unitCost = Money.ZERO, lineCost = Money.ZERO), plan)
    }

    @Test
    fun `the fixed cost rounds half away from zero`() {
        // 0,333 × 1001 = 333,333, que baja a 333. 0,500 × 1 = 0,5, que sube a 1.
        assertEquals(Money(333), untrackedCost(1001, 333))
        assertEquals(Money(1), untrackedCost(1, 500))
        // 0,499 × 1 = 0,499, que baja a 0.
        assertEquals(Money.ZERO, untrackedCost(1, 499))
    }

    @Test
    fun `an untracked line at the document cap is accepted and one above it is rejected`() {
        // Mil unidades al costo unitario máximo, 10^12 × 1000, llegan justo al tope de una línea,
        // 10^15. Una milésima más lo pasa.
        val atCap = LineStockSubject.Untracked(Money(Limit.UNIT_MONEY.max), qty(1_000_000))
        assertEquals(Money(Limit.DOCUMENT_TOTAL.max), rules.planLine(atCap).accepted().lineCost)

        val above = LineStockSubject.Untracked(Money(Limit.UNIT_MONEY.max), qty(1_000_001))
        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), rules.planLine(above))

        // Un millón de unidades, el tope de cantidad, al costo unitario máximo dan 10^18.
        val largest = LineStockSubject.Untracked(
            Money(Limit.UNIT_MONEY.max),
            qty(Limit.LINE_QUANTITY_MILLI.max),
        )
        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), rules.planLine(largest))
    }

    @Test
    fun `an untracked line that does not fit in a long is rejected`() {
        val subject = LineStockSubject.Untracked(Money(Long.MAX_VALUE), qty(2000))
        assertEquals(Checked.Overflow, rules.planLine(subject))
    }

    @Test
    fun `a tracked line takes its share of the stock value and keeps the average`() {
        // 10 unidades que valen 12000. Salen 3: 12000 × 3 / 10 = 3600, y quedan 7 por 8400.
        val plan = rules.planLine(LineStockSubject.Tracked(state(10_000, 1200, 12_000), qty(3000)))

        val expected = StockExit(qty(3000), Money(1200), Money(3600), state(7000, 1200, 8400))
        assertEquals(Checked.Value(LineStockPlan.Direct(expected)), plan)
        assertEquals(LineStockEffect.DIRECT, plan.accepted().stockEffect)
        assertEquals(Money(3600), plan.accepted().lineCost)
    }

    @Test
    fun `selling 3 with 1 in stock leaves minus 2 and is never blocked`() {
        // 1 unidad que vale 500, a 500. Salen 3: la que hay se lleva 500, y las 2 que faltan salen
        // al promedio, 2 × 500 = 1000. Quedan −2 sin valor.
        val plan = rules.planLine(
            LineStockSubject.Tracked(state(1000, 500, 500), qty(3000)),
        ).accepted()

        val exit = (plan as LineStockPlan.Direct).exit
        assertEquals(StockExit(qty(3000), Money(500), Money(1500), state(-2000, 500, 0)), exit)
        // La fila de venta resta 3 y entra al libro aunque deje el saldo negativo.
        val key = StockKey(productId = 7L, locationId = 1L, lotId = null)
        val movement = exit.saleMovement(key)
        assertEquals(StockMovementDraft(key, StockMovementType.SALE, qty(-3000), null), movement)
        assertEquals(
            StockMovementCheck.Valid,
            StockMovementRules.check(
                movement,
                qty(1000),
                compensated = null,
                isAlreadyCompensated = false,
            ),
        )
    }

    @Test
    fun `a box of 1000 for 400 still has a cost when its average rounds to zero`() {
        // 0,4 por unidad redondea a un promedio de 0, pero vender 250 se lleva 400 × 250 / 1000.
        val plan = rules.planLine(
            LineStockSubject.Tracked(state(1_000_000, 0, 400), qty(250_000)),
        ).accepted()

        assertEquals(Money(100), plan.lineCost)
        assertEquals(Money.ZERO, (plan as LineStockPlan.Direct).exit.unitCost)
    }

    @Test
    fun `a tracked line above the document cap is rejected`() {
        // Sin stock sale al promedio: 1000 unidades a 2 × 10^15 son 2 × 10^18, sobre el tope.
        val subject = LineStockSubject.Tracked(state(0, 2_000_000_000_000_000, 0), qty(1_000_000))
        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), rules.planLine(subject))
    }

    @Test
    fun `a tracked line whose cost does not fit in a long is rejected`() {
        val subject = LineStockSubject.Tracked(state(0, Long.MAX_VALUE, 0), qty(2000))
        assertEquals(Checked.Overflow, rules.planLine(subject))
    }

    @Test
    fun `a tracked line whose stock after the exit does not fit in a long is rejected`() {
        // Sin costo, la salida cuesta cero, pero restarle una milésima al saldo mínimo desborda.
        val subject = LineStockSubject.Tracked(
            CostState(qty(Long.MIN_VALUE), Money.ZERO, Money.ZERO),
            qty(1),
        )
        assertEquals(Checked.Overflow, rules.planLine(subject))
    }

    @Test
    fun `two kits of 2 A 1 B and a service cost 9600 and move only A and B`() {
        // A vale 1000 por unidad: 10 por 10000. B vale 2500: 5 por 12500. El servicio cuesta 300
        // fijo. Dos kits consumen 4 de A, 2 de B y 2 del servicio.
        val subject = LineStockSubject.Components(
            leaves = listOf(
                ComponentLeaf.Tracked(PRODUCT_A, qty(4000)),
                ComponentLeaf.Tracked(PRODUCT_B, qty(2000)),
                ComponentLeaf.Untracked(SERVICE, qty(2000), Money(300)),
            ),
            costStates = mapOf(
                PRODUCT_A to state(10_000, 1000, 10_000),
                PRODUCT_B to state(5000, 2500, 12_500),
            ),
        )

        val plan = rules.planLine(subject).accepted() as LineStockPlan.Components

        // A: 10000 × 4 / 10 = 4000. B: 12500 × 2 / 5 = 5000. Servicio: 2 × 300 = 600.
        val exitA = StockExit(qty(4000), Money(1000), Money(4000), state(6000, 1000, 6000))
        val exitB = StockExit(qty(2000), Money(2500), Money(5000), state(3000, 2500, 7500))
        assertEquals(
            listOf(
                LeafStockPlan.Tracked(PRODUCT_A, exitA),
                LeafStockPlan.Tracked(PRODUCT_B, exitB),
                LeafStockPlan.Untracked(SERVICE, qty(2000), Money(300), Money(600)),
            ),
            plan.leaves,
        )
        assertEquals(Money(9600), plan.lineCost)
        assertEquals(LineStockEffect.COMPONENTS, plan.stockEffect)
        assertEquals(
            mapOf(PRODUCT_A to exitA.stateAfter, PRODUCT_B to exitB.stateAfter),
            plan.costStatesAfter,
        )

        // Solo las hojas con stock escriben su fila: −4000 de A y −2000 de B, ninguna del servicio
        // y ninguna del kit.
        val movements = plan.leaves.filterIsInstance<LeafStockPlan.Tracked>().map {
            it.exit.saleMovement(StockKey(it.productId, locationId = 1L, lotId = null)).quantity
        }
        assertEquals(listOf(qty(-4000), qty(-2000)), movements)
        assertEquals(listOf(true, true, false), plan.leaves.map { it.tracked })
        assertEquals(listOf(qty(4000), qty(2000), qty(2000)), plan.leaves.map { it.quantity })
        assertEquals(listOf(Money(1000), Money(2500), Money(300)), plan.leaves.map { it.unitCost })
        assertEquals(listOf(Money(4000), Money(5000), Money(600)), plan.leaves.map { it.lineCost })
    }

    @Test
    fun `a component in two leaves takes its second exit from the state the first left`() {
        // 3 unidades por 1000. La primera sale con 1000 / 3 = 333,33, que baja a 333, y quedan 2
        // por 667. La segunda sale con 667 / 2 = 333,5, que sube a 334. Desde el estado inicial
        // daría 333 otra vez.
        val subject = LineStockSubject.Components(
            leaves = listOf(
                ComponentLeaf.Tracked(PRODUCT_A, qty(1000)),
                ComponentLeaf.Tracked(PRODUCT_A, qty(1000)),
            ),
            costStates = mapOf(PRODUCT_A to state(3000, 333, 1000)),
        )

        val plan = rules.planLine(subject).accepted() as LineStockPlan.Components

        assertEquals(listOf(Money(333), Money(334)), plan.leaves.map { it.lineCost })
        assertEquals(Money(667), plan.lineCost)
        assertEquals(mapOf(PRODUCT_A to state(1000, 333, 333)), plan.costStatesAfter)
    }

    @Test
    fun `the states of products outside the combo pass through unchanged`() {
        val other = state(2000, 700, 1400)
        val subject = LineStockSubject.Components(
            leaves = listOf(ComponentLeaf.Untracked(SERVICE, qty(1000), Money(300))),
            costStates = mapOf(PRODUCT_B to other),
        )

        val plan = rules.planLine(subject).accepted() as LineStockPlan.Components

        assertEquals(mapOf(PRODUCT_B to other), plan.costStatesAfter)
        assertEquals(Money(300), plan.lineCost)
    }

    @Test
    fun `a combo without components costs zero and moves nothing`() {
        val plan = rules.planLine(LineStockSubject.Components(emptyList(), emptyMap())).accepted()
        assertEquals(LineStockPlan.Components(emptyList(), Money.ZERO, emptyMap()), plan)
    }

    @Test
    fun `a tracked leaf without its cost state is a programming error`() {
        val subject = LineStockSubject.Components(
            listOf(ComponentLeaf.Tracked(PRODUCT_A, qty(1000))),
            emptyMap(),
        )
        assertThrows(IllegalArgumentException::class.java) { rules.planLine(subject) }
    }

    @Test
    fun `a combo whose leaf does not fit in a long is rejected`() {
        val subject = LineStockSubject.Components(
            leaves = listOf(ComponentLeaf.Tracked(PRODUCT_A, qty(2000))),
            costStates = mapOf(PRODUCT_A to state(0, Long.MAX_VALUE, 0)),
        )
        assertEquals(Checked.Overflow, rules.planLine(subject))
    }

    @Test
    fun `a combo whose leaf is above the document cap is rejected`() {
        val leaf = ComponentLeaf.Untracked(
            SERVICE,
            qty(Limit.LINE_QUANTITY_MILLI.max + 1),
            Money(Limit.UNIT_MONEY.max),
        )
        val subject = LineStockSubject.Components(listOf(leaf), emptyMap())
        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), rules.planLine(subject))
    }

    @Test
    fun `a combo whose leaves add up above the document cap is rejected`() {
        // Un millón de unidades a 6 × 10^8: cada hoja cuesta 6 × 10^14, dentro del tope de 10^15, y
        // las dos suman 1,2 × 10^15, por encima.
        val leaf = ComponentLeaf.Untracked(
            SERVICE,
            qty(Limit.LINE_QUANTITY_MILLI.max),
            Money(600_000_000),
        )
        val subject = LineStockSubject.Components(listOf(leaf, leaf), emptyMap())
        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), rules.planLine(subject))
    }

    @Test
    fun `a combo whose leaves add up to the document cap is accepted`() {
        val leaf = ComponentLeaf.Untracked(
            SERVICE,
            qty(Limit.LINE_QUANTITY_MILLI.max),
            Money(500_000_000),
        )
        val subject = LineStockSubject.Components(listOf(leaf, leaf), emptyMap())
        assertEquals(Money(Limit.DOCUMENT_TOTAL.max), rules.planLine(subject).accepted().lineCost)
    }

    @Test
    fun `each subject reports its mode and its plan the effect of that mode`() {
        val subjects = listOf(
            LineStockSubject.Tracked(state(1000, 100, 100), qty(1000)),
            LineStockSubject.Untracked(Money(100), qty(1000)),
            LineStockSubject.Components(emptyList(), emptyMap()),
        )
        assertEquals(ProductStockMode.entries, subjects.map { it.stockMode })
        for (subject in subjects) {
            assertEquals(
                rules.stockEffectOf(subject.stockMode),
                rules.planLine(subject).accepted().stockEffect,
            )
        }
    }

    @Test
    fun `a line or leaf quantity must be positive`() {
        val state = state(1000, 100, 100)
        for (quantity in listOf(qty(0), qty(-1000))) {
            assertThrows(IllegalArgumentException::class.java) {
                LineStockSubject.Tracked(state, quantity)
            }
            assertThrows(IllegalArgumentException::class.java) {
                LineStockSubject.Untracked(Money(1), quantity)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ComponentLeaf.Tracked(PRODUCT_A, quantity)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ComponentLeaf.Untracked(SERVICE, quantity, Money(1))
            }
        }
    }

    @Test
    fun `a fixed cost is never negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            LineStockSubject.Untracked(Money(-1), qty(1000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComponentLeaf.Untracked(SERVICE, qty(1000), Money(-1))
        }
    }

    private fun untrackedCost(fixedUnitCost: Long, quantity: Long): Money = rules.planLine(
        LineStockSubject.Untracked(Money(fixedUnitCost), qty(quantity)),
    ).accepted().lineCost

    private fun qty(milli: Long) = QuantityMilli(milli)

    private fun state(onHand: Long, averageCost: Long, stockValue: Long) =
        CostState(QuantityMilli(onHand), Money(averageCost), Money(stockValue))

    private fun <T> Checked<T>.accepted(): T = when (this) {
        is Checked.Value -> value
        Checked.Overflow -> throw AssertionError("Se esperaba un valor y la cuenta desbordó")
        is Checked.AboveLimit -> throw AssertionError("Se esperaba un valor y pasó el tope $limit")
    }

    private companion object {
        const val PRODUCT_A = 11L
        const val PRODUCT_B = 12L
        const val SERVICE = 13L
    }
}
