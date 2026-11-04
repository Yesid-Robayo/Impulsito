package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** El saldo, el costo promedio y el valor del stock que un producto guarda en su fila. */
internal data class ProductStockHeader(
    val onHand: QuantityMilli,
    val averageCost: Money,
    val stockValue: Money,
)

/**
 * Las tablas de [InMemoryDatabase], inmutables: cada escritura produce otra copia, así que deshacer
 * una transacción es volver a la copia de antes.
 *
 * [lastIds] guarda el último id dado por tabla, y se deshace con todo lo demás: una base con ids
 * que nunca se reutilizan tampoco consume ids en una transacción revertida. [changeSeq] cuenta los
 * cambios confirmados que aún no se respaldan.
 */
internal data class Tables(
    val purchases: Map<Long, Purchase> = emptyMap(),
    val purchaseLines: Map<Long, PurchaseLine> = emptyMap(),
    val movements: Map<Long, StockMovementRecord> = emptyMap(),
    val productStock: Map<Long, ProductStockHeader> = emptyMap(),
    val lastIds: Map<String, Long> = emptyMap(),
    val changeSeq: Long = 0L,
)

/** Una falla inyectada a propósito en una escritura. */
internal class InjectedFailure : IllegalStateException()

/**
 * Una base en memoria con transacciones de todo o nada, para probar casos de uso sin `:data`.
 *
 * Fuera de una transacción solo se lee lo confirmado. Dentro, se lee y se escribe una copia de
 * trabajo que solo se publica al confirmar: un observador nunca ve una transacción a medias, como
 * con la base real. Escribir fuera de una transacción falla, porque así se saltaría el todo o nada.
 *
 * [failure] inyecta una falla: la escritura número [failAtWrite] de la transacción lanza lo que
 * devuelva, antes de escribir nada.
 */
internal class InMemoryDatabase {
    private val committed = MutableStateFlow(Tables())
    private var working: Tables? = null
    private var writesInTransaction = 0

    var failAtWrite: Int? = null
    var failure: () -> Throwable = { InjectedFailure() }

    /** Cuántas escrituras hizo la última transacción, confirmada o no. */
    var lastTransactionWrites: Int = 0
        private set

    val inTransaction: Boolean get() = working != null

    /** Lo confirmado, que es lo único que ven los observadores. */
    val tables: StateFlow<Tables> = committed

    /** Lo que lee un repositorio: la copia de trabajo dentro de una transacción. */
    fun read(): Tables = working ?: committed.value

    fun begin() {
        check(working == null) { "Una transacción dentro de otra" }
        working = committed.value
        writesInTransaction = 0
    }

    /**
     * Confirma la copia de trabajo. Con [counted], suma un cambio sin respaldar en la misma
     * transacción; sin él, la confirma tal cual, como lo que no es un hecho del negocio.
     */
    fun commit(counted: Boolean = true) {
        val tables = checkNotNull(working) { "No hay transacción que confirmar" }
        committed.value = if (counted) tables.copy(changeSeq = tables.changeSeq + 1) else tables
        end()
    }

    fun rollback() {
        end()
    }

    /** Aplica [change] a la copia de trabajo. Falla fuera de una transacción. */
    fun write(change: (Tables) -> Tables) {
        val tables = checkNotNull(working) { "Una escritura fuera de una transacción" }
        writesInTransaction++
        if (writesInTransaction == failAtWrite) throw failure()
        working = change(tables)
    }

    /** Da el id siguiente de [table], como una clave que nunca se reutiliza. */
    fun nextId(table: String): Long {
        var id = 0L
        write { tables ->
            id = (tables.lastIds[table] ?: 0L) + 1L
            tables.copy(lastIds = tables.lastIds + (table to id))
        }
        return id
    }

    private fun end() {
        lastTransactionWrites = writesInTransaction
        working = null
    }
}
