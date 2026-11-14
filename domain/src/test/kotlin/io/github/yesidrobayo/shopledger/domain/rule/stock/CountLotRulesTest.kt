package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import org.junit.Assert.assertEquals
import org.junit.Test

/** Qué lotes de un producto se cuentan, y cómo se reparte la corrección de uno. Inventados. */
class CountLotRulesTest {
    private fun coded(id: Long, milli: Long, expiry: String? = null) = LotStock(
        lotId = id,
        code = "L-$id",
        expiryDate = expiry?.let { requireNotNull(BusinessDate.parse(it)) },
        isUnassigned = false,
        onHand = QuantityMilli(milli),
    )

    private fun unassigned(id: Long, milli: Long) = LotStock(
        lotId = id,
        code = null,
        expiryDate = null,
        isUnassigned = true,
        onHand = QuantityMilli(milli),
    )

    private fun line(id: Long?, milli: Long) = CountLotLine(id, QuantityMilli(milli))

    @Test
    fun `lots with stock go by expiry, then the undated, ties by the oldest`() {
        val lines = CountLotRules.linesOf(
            listOf(
                coded(4L, 1_000L),
                coded(3L, 2_000L, "2026-12-01"),
                coded(2L, 5_000L),
                coded(1L, 3_000L, "2026-11-10"),
                coded(5L, 0L, "2026-10-01"),
            ),
        )

        // El lote en cero no se cuenta; sin lote sin asignar no hay más filas.
        assertEquals(
            listOf(line(1L, 3_000L), line(3L, 2_000L), line(2L, 5_000L), line(4L, 1_000L)),
            lines,
        )
    }

    @Test
    fun `the unassigned lot is counted only when it has stock`() {
        assertEquals(
            listOf(line(1L, 3_000L), line(9L, -2_000L)),
            CountLotRules.linesOf(listOf(unassigned(9L, -2_000L), coded(1L, 3_000L))),
        )
        assertEquals(
            listOf(line(1L, 3_000L)),
            CountLotRules.linesOf(listOf(unassigned(9L, 0L), coded(1L, 3_000L))),
        )
    }

    @Test
    fun `without lots with stock the product is still counted on the unassigned lot`() {
        // El sin asignar existe, aunque esté en cero.
        assertEquals(
            listOf(line(9L, 0L)),
            CountLotRules.linesOf(listOf(unassigned(9L, 0L), coded(1L, 0L))),
        )
        // Todavía no existe: la línea no tiene lote, y se crea al aplicar si hace falta.
        assertEquals(listOf(line(null, 0L)), CountLotRules.linesOf(emptyList()))
    }

    @Test
    fun `what comes in goes to the lot`() {
        assertEquals(
            CountCorrectionSplit(QuantityMilli(2_000L), QuantityMilli.ZERO),
            CountLotRules.splitCorrection(QuantityMilli(2_000L), QuantityMilli(1_000L)),
        )
    }

    @Test
    fun `what goes out leaves the lot down to zero and the rest from the unassigned lot`() {
        // Alcanza: todo sale del lote.
        assertEquals(
            CountCorrectionSplit(QuantityMilli(-3_000L), QuantityMilli.ZERO),
            CountLotRules.splitCorrection(QuantityMilli(-3_000L), QuantityMilli(5_000L)),
        )
        // Con 1 en el lote, −3 da −1 al lote y −2 al sin asignar.
        assertEquals(
            CountCorrectionSplit(QuantityMilli(-1_000L), QuantityMilli(-2_000L)),
            CountLotRules.splitCorrection(QuantityMilli(-3_000L), QuantityMilli(1_000L)),
        )
        // Un lote sin nada, o en negativo por un libro dañado, no da nada.
        assertEquals(
            CountCorrectionSplit(QuantityMilli.ZERO, QuantityMilli(-3_000L)),
            CountLotRules.splitCorrection(QuantityMilli(-3_000L), QuantityMilli(-1_000L)),
        )
    }
}
