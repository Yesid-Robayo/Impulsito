package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
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
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_VOID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba de propiedades del libro de stock: mil secuencias al azar, con semilla fija, de compras,
 * ventas, ajustes, correcciones de costo, devoluciones, producción, transferencias y anulaciones,
 * también repetidas, más filas mal formadas.
 *
 * Tras cada operación comprueba, contra oráculos escritos aquí y no con el código que prueba: que
 * el libro solo creció; que el saldo de cada producto y de cada parte es la suma de sus filas; que
 * el saldo guardado en cada fila sigue el orden del libro; que cada anulación compensó una sola
 * vez, en la misma parte y con la cantidad opuesta; que el saldo es lo que suman los documentos
 * vigentes; que un rechazo no dejó rastro; y que la alerta avisó solo al cruzar el mínimo hacia
 * abajo.
 *
 * Al final cuenta cuántas veces pasó por cada camino, para que un cambio del generador no deje la
 * prueba en verde sin probar nada. Si falla, el mensaje trae la semilla y el paso para repetirla.
 */
class StockLedgerPropertyTest {
    @Test
    fun `a thousand random sequences keep the ledger the balances and the alerts consistent`() {
        val stats = Stats()
        repeat(SEQUENCES) { index -> runSequence(BASE_SEED + index, stats) }

        assertEquals(SEQUENCES, stats.sequences)
        val paths =
            mapOf(
                "documentos escritos" to stats.committed,
                "ventas, nunca rechazadas" to stats.sales,
                "anulaciones" to stats.voids,
                "anulaciones repetidas" to stats.repeatedVoids,
                "correcciones de costo que no se anulan" to stats.notCompensable,
                "rechazos por el tope de stock" to stats.capRejections,
                "saldos negativos" to stats.negativeBalances,
                "saldos sobre el tope tras una anulación o devolución" to stats.aboveCap,
                "avisos de stock bajo" to stats.crossings,
                "alertas rearmadas" to stats.rearms,
                "transacciones con dos líneas del mismo producto" to stats.repeatedProduct,
                "cruces entre líneas que no cuentan al confirmar" to stats.maskedCrossings,
            ) + MalformedKind.entries.associate { "rechazo $it" to stats.malformed.getValue(it) }
        for ((path, count) in paths) assertTrue("Ningún caso de: $path", count > 0)
    }

    private fun runSequence(seed: Long, stats: Stats) {
        val random = Random(seed)
        val shuffler = Random(seed xor SHUFFLE_SALT)
        val products = StockOperationGenerator.products(random)
        val generator = StockOperationGenerator(random, products)
        val book = InMemoryStockBook(products.associate { it.id to it.minStock })
        val documents = mutableListOf<LedgerDocument>()
        // Algunas secuencias empiezan cerca del tope, para que las entradas choquen con él.
        val startsNearLimit = random.nextInt(100) < NEAR_LIMIT_PERCENT
        val count = random.nextInt(MIN_OPERATIONS, MAX_OPERATIONS + 1)
        for (step in 0 until count) {
            val operation =
                if (step == 0 && startsNearLimit) {
                    generator.openingNearLimit()
                } else {
                    generator.next(book, documents)
                }
            at(seed, step) {
                val before = Snapshot.of(book)
                perform(operation, book, documents, before, stats)
                assertInvariants(book, documents, before, shuffler, stats)
            }
        }
        at(seed, count) { voidEverythingInAnyOrder(book, documents, shuffler) }
        stats.sequences++
    }

    private fun perform(
        operation: StockOperation,
        book: InMemoryStockBook,
        documents: MutableList<LedgerDocument>,
        before: Snapshot,
        stats: Stats,
    ) {
        when (operation) {
            is StockOperation.Record -> record(operation, book, documents, before, stats)

            is StockOperation.Void -> void(documents[operation.documentIndex], book, before, stats)

            is StockOperation.Malformed -> {
                val outcome = book.record(operation.drafts)
                assertEquals(operation.kind.name, BookOutcome.Rejected(operation.expected), outcome)
                assertUnchanged(before, book)
                stats.malformed[operation.kind] = stats.malformed.getValue(operation.kind) + 1
            }
        }
    }

    private fun record(
        operation: StockOperation.Record,
        book: InMemoryStockBook,
        documents: MutableList<LedgerDocument>,
        before: Snapshot,
        stats: Stats,
    ) {
        val outcome = book.record(operation.drafts)
        if (exceedsCap(before.balances, operation.drafts)) {
            assertEquals(BookOutcome.Rejected(StockMovementCheck.AboveOnHandLimit), outcome)
            assertUnchanged(before, book)
            stats.capRejections++
            return
        }
        // Vender nunca se bloquea por stock, y nada que no sea una entrada choca con el tope.
        val committed = outcome as? BookOutcome.Committed ?: throw rejected(outcome)
        val ids = (before.rows.size + 1L)..(before.rows.size.toLong() + operation.drafts.size)
        documents += LedgerDocument(operation.kind, ids.toList())
        assertTransitions(before, book, operation.drafts, committed, stats)
        stats.committed++
        if (operation.kind == DocumentKind.SALE) stats.sales++
        val products = operation.drafts.map { it.key.productId }
        if (products.size != products.distinct().size) stats.repeatedProduct++
    }

    private fun void(
        document: LedgerDocument,
        book: InMemoryStockBook,
        before: Snapshot,
        stats: Stats,
    ) {
        val outcome = book.void(document.movementIds)
        when {
            document.kind == DocumentKind.COST_CORRECTION -> {
                assertEquals(BookOutcome.NotVoidable(StockCompensation.NotCompensable), outcome)
                assertUnchanged(before, book)
                stats.notCompensable++
            }

            document.isVoided -> {
                assertEquals(BookOutcome.NotVoidable(StockCompensation.AlreadyCompensated), outcome)
                assertUnchanged(before, book)
                stats.repeatedVoids++
            }

            else -> {
                val committed = outcome as? BookOutcome.Committed ?: throw rejected(outcome)
                document.isVoided = true
                val compensations = book.movements.drop(before.rows.size).map { it.asDraft() }
                assertTransitions(before, book, compensations, committed, stats)
                stats.voids++
            }
        }
    }

    /**
     * Compara lo que decidió la alerta con un umbral simple: avisa si el saldo pasó de encima del
     * mínimo a no encima, y rearma si pasó de no encima a encima, mirando solo el saldo antes de la
     * transacción y después. Vale porque la alerta sana está activa si y solo si el producto está
     * bajo, y eso también se comprueba.
     */
    private fun assertTransitions(
        before: Snapshot,
        book: InMemoryStockBook,
        drafts: List<StockMovementDraft>,
        committed: BookOutcome.Committed,
        stats: Stats,
    ) {
        val expected =
            drafts.map { it.key.productId }.distinct().associateWith { productId ->
                val min = book.minStockOf(productId).milli
                val start = before.balances.getValue(productId).milli
                val end = book.balances.getValue(productId).milli
                when {
                    start > min && end <= min ->
                        LowStockTransition.Crossed(QuantityMilli(end), QuantityMilli(min))

                    start <= min && end > min -> LowStockTransition.Rearmed

                    else -> LowStockTransition.Unchanged
                }
            }
        assertEquals(expected, committed.transitions)
        stats.crossings += expected.values.count { it is LowStockTransition.Crossed }
        stats.rearms += expected.values.count { it == LowStockTransition.Rearmed }
        stats.maskedCrossings += maskedCrossings(before, book, drafts)
    }

    /**
     * Cuántos productos habrían avisado una cantidad distinta de veces si la alerta se evaluara
     * línea por línea en vez de una vez al confirmar.
     */
    private fun maskedCrossings(
        before: Snapshot,
        book: InMemoryStockBook,
        drafts: List<StockMovementDraft>,
    ): Int = drafts.groupBy { it.key.productId }.count { (productId, lines) ->
        val min = book.minStockOf(productId).milli
        var balance = before.balances.getValue(productId).milli
        var lineCrossings = 0
        for (line in lines) {
            val next = balance + line.quantity.milli
            if (balance > min && next <= min) lineCrossings++
            balance = next
        }
        val start = before.balances.getValue(productId).milli
        val transactionCrossings = if (start > min && balance <= min) 1 else 0
        lineCrossings != transactionCrossings
    }

    private fun assertInvariants(
        book: InMemoryStockBook,
        documents: List<LedgerDocument>,
        before: Snapshot,
        shuffler: Random,
        stats: Stats,
    ) {
        val rows = book.movements
        val balances = book.balances
        val balancesByKey = book.balancesByKey

        // El libro solo crece: nada de lo que había cambió ni desapareció.
        assertEquals(before.rows, rows.take(before.rows.size))

        // El saldo de cada producto es la suma de sus filas, y la suma de sus partes.
        val derived = StockLedger.onHandByProduct(rows)
        for (productId in book.productIds) {
            val balance = balances.getValue(productId)
            assertEquals(derived[productId] ?: QuantityMilli.ZERO, balance)
            assertEquals(balance.milli, totalOf(rows.filter { it.key.productId == productId }))
            val parts = balancesByKey.filterKeys { it.productId == productId }.values
            assertEquals(balance.milli, parts.sumOf { it.milli })
        }
        assertEquals(StockLedger.onHandByKey(rows), balancesByKey)

        // El saldo guardado en cada fila sigue el orden del libro, en cualquier orden de lectura.
        assertEquals(book.recordedBalancesAfter, StockLedger.balancesAfter(rows.shuffled(shuffler)))

        assertCompensations(rows)
        assertEffectiveLedger(rows, balances)
        assertDocuments(rows, documents, balances)

        // La alerta sana está activa si y solo si el producto está bajo.
        for (productId in book.productIds) {
            val isLow = balances.getValue(productId) <= book.minStockOf(productId)
            assertEquals("producto $productId", isLow, productId in book.productsWithActiveAlert)
        }

        if (balances.values.any { it.milli < 0L }) stats.negativeBalances++
        if (balances.values.any { it.milli > ON_HAND_CAP_MILLI }) stats.aboveCap++
    }

    /**
     * Cada compensatorio anula una sola fila anterior, en su misma parte y con la cantidad opuesta.
     */
    private fun assertCompensations(rows: List<StockMovement>) {
        val reversed = rows.mapNotNull { it.reversesMovementId }
        assertEquals("una fila compensada dos veces", reversed.size, reversed.toSet().size)
        val byId = rows.associateBy { it.id }
        for (compensating in rows) {
            val original = compensating.reversesMovementId?.let(byId::getValue) ?: continue
            assertTrue(original.id < compensating.id)
            assertEquals(original.key, compensating.key)
            assertEquals(-original.quantity.milli, compensating.quantity.milli)
            assertEquals(EXPECTED_VOID_TYPE.getValue(original.type), compensating.type)
        }
    }

    /**
     * El libro efectivo no tiene filas anuladas ni compensatorios, y suma lo mismo que el libro.
     */
    private fun assertEffectiveLedger(
        rows: List<StockMovement>,
        balances: Map<Long, QuantityMilli>,
    ) {
        val effective = StockLedger.effective(rows)
        val compensated = rows.mapNotNull { it.reversesMovementId }.toSet()
        assertTrue(effective.none { it.reversesMovementId != null || it.id in compensated })
        assertEquals(effective.map { it.id }, effective.map { it.id }.sorted())
        for ((productId, balance) in balances) {
            assertEquals(balance.milli, totalOf(effective.filter { it.key.productId == productId }))
        }
    }

    /**
     * Cada documento anulado tiene todas sus filas compensadas una vez, y uno vigente ninguna. El
     * saldo de cada producto es lo que suman los documentos vigentes, sin mirar los compensatorios.
     */
    private fun assertDocuments(
        rows: List<StockMovement>,
        documents: List<LedgerDocument>,
        balances: Map<Long, QuantityMilli>,
    ) {
        val compensated = rows.mapNotNull { it.reversesMovementId }.toSet()
        val byId = rows.associateBy { it.id }
        val fromDocuments = mutableMapOf<Long, Long>()
        for (document in documents) {
            for (id in document.movementIds) {
                assertEquals(document.isVoided, id in compensated)
                if (!document.isVoided) {
                    val row = byId.getValue(id)
                    val productId = row.key.productId
                    fromDocuments[productId] = (fromDocuments[productId] ?: 0L) + row.quantity.milli
                }
            }
        }
        for ((productId, balance) in balances) {
            assertEquals(fromDocuments[productId] ?: 0L, balance.milli)
        }
    }

    /**
     * Anula todos los documentos vigentes en dos órdenes distintos, sobre dos copias: los dos
     * caminos dejan todo en cero, las mismas partes y el mismo libro efectivo, que solo conserva
     * las correcciones de costo, porque esas no se anulan.
     */
    private fun voidEverythingInAnyOrder(
        book: InMemoryStockBook,
        documents: List<LedgerDocument>,
        shuffler: Random,
    ) {
        val pending = documents.filter { !it.isVoided && it.kind != DocumentKind.COST_CORRECTION }
        val results =
            List(2) {
                val copy = book.copy()
                for (document in pending.shuffled(shuffler)) {
                    assertTrue(copy.void(document.movementIds) is BookOutcome.Committed)
                }
                copy
            }
        val (first, second) = results
        for (copy in results) {
            assertTrue(copy.balances.values.all { it == QuantityMilli.ZERO })
            assertTrue(StockLedger.effective(copy.movements).all { it.type == COST_CORRECTION })
            assertEquals(copy.productIds, copy.productsWithActiveAlert)
        }
        assertEquals(first.balancesByKey, second.balancesByKey)
        assertEquals(
            StockLedger.effective(first.movements).map { it.id },
            StockLedger.effective(second.movements).map { it.id },
        )
    }

    private fun assertUnchanged(before: Snapshot, book: InMemoryStockBook) {
        assertEquals(before, Snapshot.of(book))
    }

    /**
     * Si alguna entrada que escribe la persona dejaría su producto sobre el tope de ocho millones
     * de unidades, contando las líneas anteriores de la misma transacción.
     */
    private fun exceedsCap(
        balances: Map<Long, QuantityMilli>,
        drafts: List<StockMovementDraft>,
    ): Boolean {
        val running = balances.mapValues { it.value.milli }.toMutableMap()
        for (draft in drafts) {
            val productId = draft.key.productId
            val quantity = draft.quantity.milli
            val after = running.getValue(productId) + quantity
            if (draft.type in WRITTEN_ENTRIES && quantity > 0L && after > ON_HAND_CAP_MILLI) {
                return true
            }
            running[productId] = after
        }
        return false
    }

    private fun totalOf(rows: List<StockMovement>): Long = rows.sumOf { it.quantity.milli }

    private fun StockMovement.asDraft() =
        StockMovementDraft(key, type, quantity, reversesMovementId)

    private fun rejected(outcome: BookOutcome) = AssertionError("Se rechazó: $outcome")

    /** Agrega la semilla y el paso a cualquier fallo, para poder repetir la secuencia. */
    private inline fun at(seed: Long, step: Int, block: () -> Unit) {
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError("Semilla $seed, paso $step: ${failure.message}", failure)
        } catch (failure: RuntimeException) {
            throw AssertionError("Semilla $seed, paso $step: $failure", failure)
        }
    }

    /** Lo que una transacción rechazada no puede cambiar. */
    private data class Snapshot(
        val rows: List<StockMovement>,
        val balances: Map<Long, QuantityMilli>,
        val balancesByKey: Map<StockKey, QuantityMilli>,
        val balancesAfter: Map<Long, QuantityMilli>,
        val activeAlerts: Set<Long>,
    ) {
        companion object {
            fun of(book: InMemoryStockBook) = Snapshot(
                rows = book.movements,
                balances = book.balances,
                balancesByKey = book.balancesByKey,
                balancesAfter = book.recordedBalancesAfter,
                activeAlerts = book.productsWithActiveAlert,
            )
        }
    }

    private class Stats {
        var sequences = 0
        var committed = 0
        var sales = 0
        var voids = 0
        var repeatedVoids = 0
        var notCompensable = 0
        var capRejections = 0
        var negativeBalances = 0
        var aboveCap = 0
        var crossings = 0
        var rearms = 0
        var repeatedProduct = 0
        var maskedCrossings = 0
        val malformed = MalformedKind.entries.associateWithTo(mutableMapOf()) { 0 }
    }

    private companion object {
        const val SEQUENCES = 1000
        const val BASE_SEED = 20_261_103L
        const val SHUFFLE_SALT = 0x5EED_5EEDL
        const val MIN_OPERATIONS = 10
        const val MAX_OPERATIONS = 50
        const val NEAR_LIMIT_PERCENT = 30

        /** Ocho millones de unidades en milésimas: el tope de stock, escrito a mano. */
        const val ON_HAND_CAP_MILLI = 8_000_000_000L

        /** Las entradas que escribe la persona: las únicas que validan el tope de stock. */
        val WRITTEN_ENTRIES = setOf(OPENING_BALANCE, PURCHASE, ADJUSTMENT, PRODUCTION_IN)

        /** Qué compensatorio anula cada tipo, escrito a mano desde la tabla de tipos. */
        val EXPECTED_VOID_TYPE: Map<StockMovementType, StockMovementType> =
            mapOf(
                OPENING_BALANCE to ADJUSTMENT_VOID,
                ADJUSTMENT to ADJUSTMENT_VOID,
                PURCHASE to PURCHASE_VOID,
                SALE to SALE_VOID,
                SALE_RETURN to SALE_RETURN_VOID,
                PURCHASE_RETURN to PURCHASE_RETURN_VOID,
                PRODUCTION_OUT to PRODUCTION_VOID,
                PRODUCTION_IN to PRODUCTION_VOID,
                TRANSFER_OUT to TRANSFER_VOID,
                TRANSFER_IN to TRANSFER_VOID,
            )
    }
}
