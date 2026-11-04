package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.COST_CORRECTION
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.OPENING_BALANCE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_OUT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_RETURN_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_OUT
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

// Secuencias aleatorias con semilla fija: una falla siempre se repite igual. Cada paso escribe su
// fila como lo haría la app, con los valores del momento, y el costo se lleva paso a paso.
class CostReplayPropertyTest {
    @Test
    fun `the cost kept step by step always matches the replay of the ledger`() {
        val random = Random(SEED)
        repeat(SEQUENCES) { sequence ->
            val ledger = SimulatedLedger(random, withVoids = true)
            repeat(random.nextInt(1, MAX_STEPS)) { ledger.step() }

            val message = "secuencia $sequence"
            assertEquals(
                message,
                CostReplayResult.Replayed(ledger.state),
                ledger.replay(ledger.rows),
            )
            // El saldo es la suma de todas las filas, también las anuladas y las transferencias.
            assertEquals(
                message,
                ledger.rows.sumOf {
                    it.quantity.milli
                },
                ledger.state.onHand.milli,
            )
        }
    }

    @Test
    fun `voided documents weigh nothing whatever order they were voided in`() {
        val random = Random(SEED + 1)
        repeat(SEQUENCES) { sequence ->
            val ledger = SimulatedLedger(random, withVoids = false)
            repeat(random.nextInt(2, MAX_STEPS)) { ledger.step() }
            val documents = ledger.voidableRows().shuffled(random).take(random.nextInt(1, 4))
            val firstVoidId = ledger.rows.size + 1L

            // El resultado es el que habría si esos documentos nunca hubieran existido, con lo
            // demás tal como se guardó.
            val expected = ledger.replay(ledger.rows - documents.toSet())
            val message = "secuencia $sequence"
            assertEquals(
                message,
                expected,
                ledger.replay(ledger.rows + voids(documents, firstVoidId)),
            )
            assertEquals(
                message,
                expected,
                ledger.replay(ledger.rows + voids(documents.reversed(), firstVoidId)),
            )
        }
    }

    private fun voids(documents: List<CostMovement>, firstId: Long) =
        documents.mapIndexed { index, document -> voidOf(document, firstId + index) }

    private class SimulatedLedger(private val random: Random, private val withVoids: Boolean) {
        private val cost = WeightedAverageCost
        private val initialCost = Money(random.nextLong(0, 3000))
        val rows = mutableListOf<CostMovement>()
        var state = CostState(QuantityMilli.ZERO, initialCost)
            private set

        fun replay(movements: List<CostMovement>) = cost.replayCostState(initialCost, movements)

        fun voidableRows(): List<CostMovement> {
            val reversed = rows.mapNotNull { it.reversesMovementId }.toSet()
            return rows.filter {
                it.reversesMovementId == null && it.id !in reversed && it.type in VOID_TYPE
            }
        }

        fun step() {
            when (random.nextInt(if (withVoids) 10 else 9)) {
                0 -> entry(PURCHASE)
                1 -> entry(if (rows.isEmpty()) OPENING_BALANCE else SALE_RETURN)
                2 -> entry(PRODUCTION_IN)
                3 -> exit(SALE)
                4 -> exit(if (random.nextBoolean()) PRODUCTION_OUT else ADJUSTMENT)
                5 -> positiveAdjustment()
                6 -> correction()
                7 -> supplierReturn()
                8 -> transfer()
                else -> voidOne()
            }
        }

        private fun entry(type: StockMovementType) {
            val quantity = quantity()
            val total = Money(random.nextLong(0, 50_000))
            state = cost.entry(state, quantity, total).accepted()
            add(type, quantity, unitCostOf(total, quantity), total)
        }

        private fun exit(type: StockMovementType) {
            val quantity = quantity()
            // La fila congela el costo del momento.
            val unitCost = state.averageCost
            val total = cost.exitCost(state, quantity).accepted()
            state = cost.exit(state, quantity)
            add(type, -quantity, unitCost, -total)
        }

        private fun positiveAdjustment() {
            val quantity = quantity()
            val total = cost.positiveAdjustmentCost(state, quantity).accepted()
            state = cost.positiveAdjustment(state, quantity)
            add(ADJUSTMENT, quantity, state.averageCost, total)
        }

        private fun correction() {
            val newCost = Money(random.nextLong(0, 3000))
            state = cost.correctCost(state, newCost)
            add(COST_CORRECTION, QuantityMilli.ZERO, newCost, Money.ZERO)
        }

        private fun supplierReturn() {
            val quantity = quantity()
            val total = Money(random.nextLong(0, 50_000))
            state = cost.supplierReturn(state, quantity, total).accepted()
            add(PURCHASE_RETURN, -quantity, unitCostOf(total, quantity), -total)
        }

        private fun transfer() {
            val quantity = quantity()
            add(TRANSFER_OUT, -quantity, Money.ZERO, Money.ZERO)
            add(TRANSFER_IN, quantity, Money.ZERO, Money.ZERO)
        }

        private fun voidOne() {
            val target = voidableRows().randomOrNull(random) ?: return
            rows += voidOf(target, rows.size + 1L)
            // Tras anular, el costo se recalcula reproduciendo el libro.
            state = when (val replayed = replay(rows)) {
                is CostReplayResult.Replayed -> replayed.state

                is CostReplayResult.InvalidMovement -> throw AssertionError(
                    "Fila imposible: $replayed",
                )

                is CostReplayResult.Overflow -> throw AssertionError("No cupo en Long: $replayed")
            }
        }

        // Unidades enteras o fracciones, para cubrir productos por unidad y por peso.
        private fun quantity() = if (random.nextBoolean()) {
            QuantityMilli.ofUnits(random.nextLong(1, 20))
        } else {
            QuantityMilli(random.nextLong(1, 20_000))
        }

        private fun unitCostOf(total: Money, quantity: QuantityMilli) =
            Money(roundDiv(Math.multiplyExact(total.minor, 1000L), quantity.milli))

        private fun add(
            type: StockMovementType,
            quantity: QuantityMilli,
            unitCost: Money,
            total: Money,
        ) {
            rows += CostMovement(rows.size + 1L, type, quantity, unitCost, total)
        }
    }

    private companion object {
        const val SEED = 20_261_103
        const val SEQUENCES = 1000
        const val MAX_STEPS = 60

        // La anulación de cada documento. Una corrección de costo no se anula: se corrige con otra.
        val VOID_TYPE =
            mapOf(
                OPENING_BALANCE to ADJUSTMENT_VOID,
                ADJUSTMENT to ADJUSTMENT_VOID,
                PURCHASE to PURCHASE_VOID,
                SALE to SALE_VOID,
                SALE_RETURN to SALE_RETURN_VOID,
                PURCHASE_RETURN to PURCHASE_RETURN_VOID,
                PRODUCTION_OUT to PRODUCTION_VOID,
                PRODUCTION_IN to PRODUCTION_VOID,
            )

        fun voidOf(target: CostMovement, id: Long) = CostMovement(
            id = id,
            type = requireNotNull(VOID_TYPE[target.type]) { "Ese tipo no se anula" },
            quantity = -target.quantity,
            unitCost = target.unitCost,
            totalCost = -target.totalCost,
            reversesMovementId = target.id,
        )

        fun <T> Checked<T>.accepted(): T = when (this) {
            is Checked.Value -> value
            Checked.Overflow -> throw AssertionError("La cuenta no cupo en Long")
            is Checked.AboveLimit -> throw AssertionError("La cuenta pasó el tope $limit")
        }
    }
}
