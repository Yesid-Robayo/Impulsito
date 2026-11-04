package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.QuantitySign
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import kotlin.random.Random

/** Los documentos que la prueba registra. Cada uno escribe sus filas en una transacción. */
internal enum class DocumentKind {
    OPENING_BALANCE,
    PURCHASE,
    SALE,
    ADJUSTMENT,
    COST_CORRECTION,
    SALE_RETURN,
    PURCHASE_RETURN,
    PRODUCTION,
    TRANSFER,
}

/** Las filas mal formadas que la prueba intenta escribir, y que las reglas deben rechazar. */
internal enum class MalformedKind {
    WRONG_SIGN,
    UNEXPECTED_COMPENSATION,
    MISSING_ORIGINAL,
    CROSS_COMPENSATION,
    DIFFERENT_KEY,
    NOT_OPPOSITE,
    ALREADY_COMPENSATED,
}

/** Un documento ya escrito: sus filas originales y si se anuló. */
internal class LedgerDocument(val kind: DocumentKind, val movementIds: List<Long>) {
    var isVoided: Boolean = false
}

/** Un producto inventado de la prueba. [lots] tiene `null` si el producto no lleva lotes. */
internal data class ProductSetup(
    val id: Long,
    val minStock: QuantityMilli,
    val allowsFraction: Boolean,
    val lots: List<Long?>,
)

/** Una operación que la prueba intenta sobre el libro. */
internal sealed interface StockOperation {
    data class Record(val kind: DocumentKind, val drafts: List<StockMovementDraft>) : StockOperation

    data class Void(val documentIndex: Int) : StockOperation

    data class Malformed(
        val kind: MalformedKind,
        val drafts: List<StockMovementDraft>,
        val expected: StockMovementCheck,
    ) : StockOperation
}

/**
 * Generador propio de operaciones al azar, en Kotlin puro y con la semilla que recibe [random].
 *
 * Mezcla cantidades pequeñas, con y sin fracción, y otras enormes, hasta el tope de una línea, para
 * que algunas secuencias choquen con el tope de stock. Vende sin mirar el saldo, así que el stock
 * queda negativo a menudo, y anula documentos al azar, también los ya anulados.
 */
internal class StockOperationGenerator(
    private val random: Random,
    private val products: List<ProductSetup>,
) {
    /** La primera operación de una secuencia cerca del tope: un inventario inicial enorme. */
    fun openingNearLimit(): StockOperation.Record {
        val product = products.first()
        val quantity = random.nextLong(6_000_000_000L, 8_000_000_001L)
        return StockOperation.Record(
            DocumentKind.OPENING_BALANCE,
            listOf(draft(keyOf(product), StockMovementType.OPENING_BALANCE, quantity)),
        )
    }

    fun next(book: InMemoryStockBook, documents: List<LedgerDocument>): StockOperation {
        val roll = random.nextInt(100)
        return when {
            roll < 18 -> purchase()
            roll < 40 -> sale()
            roll < 48 -> adjustment()
            roll < 51 -> costCorrection()
            roll < 54 -> openingBalance(book) ?: purchase()
            roll < 58 -> lines(DocumentKind.SALE_RETURN, StockMovementType.SALE_RETURN, 1)
            roll < 62 -> lines(DocumentKind.PURCHASE_RETURN, StockMovementType.PURCHASE_RETURN, -1)
            roll < 66 -> production() ?: sale()
            roll < 70 -> transfer()
            roll < 88 -> if (documents.isEmpty()) purchase() else voidOf(documents)
            else -> malformed(book)
        }
    }

    private fun purchase() = lines(DocumentKind.PURCHASE, StockMovementType.PURCHASE, 1)

    private fun sale() = lines(DocumentKind.SALE, StockMovementType.SALE, -1)

    private fun lines(
        kind: DocumentKind,
        type: StockMovementType,
        sign: Long,
    ): StockOperation.Record {
        val drafts =
            List(random.nextInt(1, 4)) {
                val product = products.random(random)
                draft(keyOf(product), type, sign * quantity(product))
            }
        return StockOperation.Record(kind, drafts)
    }

    private fun adjustment(): StockOperation.Record {
        val product = products.random(random)
        val sign = if (random.nextBoolean()) 1L else -1L
        return StockOperation.Record(
            DocumentKind.ADJUSTMENT,
            listOf(draft(keyOf(product), StockMovementType.ADJUSTMENT, sign * quantity(product))),
        )
    }

    private fun costCorrection(): StockOperation.Record {
        val product = products.random(random)
        return StockOperation.Record(
            DocumentKind.COST_CORRECTION,
            listOf(draft(keyOf(product), StockMovementType.COST_CORRECTION, 0L)),
        )
    }

    /** Un inventario inicial solo entra si el producto no tiene filas vigentes en su libro. */
    private fun openingBalance(book: InMemoryStockBook): StockOperation.Record? {
        val effective = StockLedger.effective(book.movements)
        val product =
            products.filter { setup -> effective.none { it.key.productId == setup.id } }
                .randomOrNull(random) ?: return null
        return StockOperation.Record(
            DocumentKind.OPENING_BALANCE,
            listOf(draft(keyOf(product), StockMovementType.OPENING_BALANCE, quantity(product))),
        )
    }

    /** Consume uno o dos insumos y produce otro producto. Necesita al menos dos productos. */
    private fun production(): StockOperation.Record? {
        if (products.size < 2) return null
        val output = products.random(random)
        val inputs = products.filter { it != output }.shuffled(random).take(random.nextInt(1, 3))
        val drafts =
            inputs.map { draft(keyOf(it), StockMovementType.PRODUCTION_OUT, -quantity(it)) } +
                draft(keyOf(output), StockMovementType.PRODUCTION_IN, quantity(output))
        return StockOperation.Record(DocumentKind.PRODUCTION, drafts)
    }

    /** Mueve stock de una ubicación a la otra, en el mismo lote. */
    private fun transfer(): StockOperation.Record {
        val product = products.random(random)
        val lot = product.lots.random(random)
        val origin = random.nextLong(1L, 3L)
        val from = StockKey(product.id, origin, lot)
        val to = StockKey(product.id, 3L - origin, lot)
        val quantity = quantity(product)
        return StockOperation.Record(
            DocumentKind.TRANSFER,
            listOf(
                draft(from, StockMovementType.TRANSFER_OUT, -quantity),
                draft(to, StockMovementType.TRANSFER_IN, quantity),
            ),
        )
    }

    private fun voidOf(documents: List<LedgerDocument>): StockOperation.Void =
        StockOperation.Void(random.nextInt(documents.size))

    /**
     * Una fila mal formada, a veces después de una o dos ventas válidas, para comprobar que el
     * rechazo revierte la transacción entera. Si el libro todavía no tiene la fila que el caso
     * necesita, cae en un signo equivocado, que no necesita ninguna.
     */
    private fun malformed(book: InMemoryStockBook): StockOperation.Malformed {
        val rows = book.movements
        val kind = MalformedKind.entries.random(random)
        val bad =
            when (kind) {
                MalformedKind.WRONG_SIGN -> null
                MalformedKind.UNEXPECTED_COMPENSATION -> unexpectedCompensation(rows)
                MalformedKind.MISSING_ORIGINAL -> missingOriginal(rows)
                MalformedKind.CROSS_COMPENSATION -> crossCompensation(rows)
                MalformedKind.DIFFERENT_KEY -> differentKey(rows, book)
                MalformedKind.NOT_OPPOSITE -> notOpposite(rows, book)
                MalformedKind.ALREADY_COMPENSATED -> alreadyCompensated(rows, book)
            } ?: (MalformedKind.WRONG_SIGN to wrongSign())
        val validPrefix = List(random.nextInt(0, 3)) { sale().drafts.first() }
        return StockOperation.Malformed(bad.first, validPrefix + bad.second, expectedFor(bad.first))
    }

    private fun expectedFor(kind: MalformedKind): StockMovementCheck = when (kind) {
        MalformedKind.WRONG_SIGN -> StockMovementCheck.WrongQuantitySign
        MalformedKind.UNEXPECTED_COMPENSATION -> StockMovementCheck.UnexpectedCompensation
        MalformedKind.MISSING_ORIGINAL -> StockMovementCheck.MissingCompensatedMovement
        MalformedKind.CROSS_COMPENSATION -> StockMovementCheck.CrossCompensation
        MalformedKind.DIFFERENT_KEY -> StockMovementCheck.DifferentStockKey
        MalformedKind.NOT_OPPOSITE -> StockMovementCheck.QuantityNotOpposite
        MalformedKind.ALREADY_COMPENSATED -> StockMovementCheck.AlreadyCompensated
    }

    private fun wrongSign(): StockMovementDraft {
        val type = StockMovementType.entries.random(random)
        val product = products.random(random)
        val units = quantity(product)
        val milli =
            when (type.quantitySign) {
                QuantitySign.POSITIVE -> -units
                QuantitySign.NEGATIVE -> units
                QuantitySign.NON_ZERO -> 0L
                QuantitySign.ZERO -> if (random.nextBoolean()) units else -units
            }
        return draft(keyOf(product), type, milli)
    }

    private fun unexpectedCompensation(
        rows: List<StockMovement>,
    ): Pair<MalformedKind, StockMovementDraft>? {
        val target = rows.randomOrNull(random) ?: return null
        val type = StockMovementType.entries.filter { !it.isCompensating }.random(random)
        val product = products.random(random)
        val draft = draft(keyOf(product), type, signedQuantity(type, product), target.id)
        return MalformedKind.UNEXPECTED_COMPENSATION to draft
    }

    private fun missingOriginal(
        rows: List<StockMovement>,
    ): Pair<MalformedKind, StockMovementDraft> {
        val type = StockMovementType.entries.filter { it.isCompensating }.random(random)
        val product = products.random(random)
        val reverses = if (random.nextBoolean()) null else rows.size + 1_000L
        val draft = draft(keyOf(product), type, signedQuantity(type, product), reverses)
        return MalformedKind.MISSING_ORIGINAL to draft
    }

    private fun crossCompensation(
        rows: List<StockMovement>,
    ): Pair<MalformedKind, StockMovementDraft>? {
        val target = rows.randomOrNull(random) ?: return null
        val opposite = -target.quantity
        val type =
            StockMovementType.entries
                .filter { it.isCompensating && it != target.type.voidType }
                .random(random)
        // Con la cantidad opuesta si su signo la admite, para que solo falle el tipo.
        val milli =
            if (type.quantitySign.allows(opposite)) {
                opposite.milli
            } else {
                signedQuantity(type, products.first { it.id == target.key.productId })
            }
        return MalformedKind.CROSS_COMPENSATION to draft(target.key, type, milli, target.id)
    }

    private fun differentKey(
        rows: List<StockMovement>,
        book: InMemoryStockBook,
    ): Pair<MalformedKind, StockMovementDraft>? {
        val target = openCompensable(rows, book) ?: return null
        val key = target.key
        // Otra ubicación, otro lote u otro producto del negocio.
        val otherKey =
            buildList {
                add(key.copy(locationId = 3L - key.locationId))
                add(key.copy(lotId = (key.lotId ?: 0L) + 1_000L))
                for (other in products.filter { it.id != key.productId }) {
                    add(key.copy(productId = other.id))
                }
            }.random(random)
        val voidType = requireNotNull(target.type.voidType)
        val draft = draft(otherKey, voidType, -target.quantity.milli, target.id)
        return MalformedKind.DIFFERENT_KEY to draft
    }

    private fun notOpposite(
        rows: List<StockMovement>,
        book: InMemoryStockBook,
    ): Pair<MalformedKind, StockMovementDraft>? {
        val target = openCompensable(rows, book) ?: return null
        val voidType = requireNotNull(target.type.voidType)
        // El doble de la opuesta: mismo signo, otra cantidad.
        val draft = draft(target.key, voidType, -2L * target.quantity.milli, target.id)
        return MalformedKind.NOT_OPPOSITE to draft
    }

    private fun alreadyCompensated(
        rows: List<StockMovement>,
        book: InMemoryStockBook,
    ): Pair<MalformedKind, StockMovementDraft>? {
        val target =
            rows.filter { it.type.voidType != null && book.isCompensated(it.id) }
                .randomOrNull(random) ?: return null
        val voidType = requireNotNull(target.type.voidType)
        val draft = draft(target.key, voidType, -target.quantity.milli, target.id)
        return MalformedKind.ALREADY_COMPENSATED to draft
    }

    private fun openCompensable(rows: List<StockMovement>, book: InMemoryStockBook) =
        rows.filter { it.type.voidType != null && !book.isCompensated(it.id) }.randomOrNull(random)

    /** Una cantidad que respeta el signo de [type]. */
    private fun signedQuantity(type: StockMovementType, product: ProductSetup): Long =
        when (type.quantitySign) {
            QuantitySign.POSITIVE -> quantity(product)
            QuantitySign.NEGATIVE -> -quantity(product)
            QuantitySign.NON_ZERO -> (if (random.nextBoolean()) 1L else -1L) * quantity(product)
            QuantitySign.ZERO -> 0L
        }

    /**
     * Una cantidad positiva en milésimas. Casi siempre de 1 a 12 unidades, o su equivalente con
     * fracción; a veces enorme, hasta un millón de unidades, que es el tope de una línea.
     */
    private fun quantity(product: ProductSetup): Long {
        val large = random.nextInt(100) < 12
        val fraction = product.allowsFraction && random.nextBoolean()
        return when {
            large && fraction -> random.nextLong(1L, LINE_LIMIT_MILLI + 1L)
            large -> random.nextLong(1L, LINE_LIMIT_MILLI / 1000L + 1L) * 1000L
            fraction -> random.nextLong(1L, 12_001L)
            else -> random.nextLong(1L, 13L) * 1000L
        }
    }

    private fun keyOf(product: ProductSetup): StockKey =
        StockKey(product.id, random.nextLong(1L, 3L), product.lots.random(random))

    private fun draft(key: StockKey, type: StockMovementType, milli: Long, reverses: Long? = null) =
        StockMovementDraft(key, type, QuantityMilli(milli), reverses)

    companion object {
        /** Un millón de unidades: el tope de cantidad de una línea, escrito a mano. */
        private const val LINE_LIMIT_MILLI = 1_000_000_000L

        /** De uno a cuatro productos inventados, con mínimos de 0 a 8 unidades. */
        fun products(random: Random): List<ProductSetup> = (1L..random.nextLong(1L, 5L)).map { id ->
            val allowsFraction = random.nextBoolean()
            val minStock =
                if (allowsFraction) {
                    random.nextLong(0L, 8_001L)
                } else {
                    random.nextLong(0L, 9L) * 1000L
                }
            val tracksLots = random.nextBoolean()
            val lots = if (tracksLots) listOf(id * 10L + 1L, id * 10L + 2L) else listOf(null)
            ProductSetup(id, QuantityMilli(minStock), allowsFraction, lots)
        }
    }
}
