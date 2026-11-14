package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/** Qué proveedores mira la lista: todos, solo lo que no tiene proveedor, o uno. */
sealed interface ReorderSupplierFilter {
    data object All : ReorderSupplierFilter

    data object Without : ReorderSupplierFilter

    data class Of(val supplierId: Long) : ReorderSupplierFilter
}

/**
 * Lo que se pide a la lista: la ventana de ventas, los días que cubre la compra y los filtros. La
 * ubicación no va aquí: cambia lo que se lee del libro, no cómo se arma la lista.
 */
data class ReorderQuery(
    val windowDays: Long = ReorderRules.DEFAULT_WINDOW_DAYS,
    val coverDays: Long = ReorderRules.DEFAULT_COVER_DAYS,
    val supplier: ReorderSupplierFilter = ReorderSupplierFilter.All,
    val categoryId: Long? = null,
)

/**
 * Un producto que conviene reponer.
 *
 * [onHand] es lo que hay donde se mira: en todas las ubicaciones o en la elegida. [purchaseUnit]
 * es la unidad en que se compra, si tiene una, y la sugerencia ya viene en múltiplos de ella.
 * [unitCost] es el costo de una unidad base que sirve para estimar: el de reposición si se conoce,
 * si no el promedio.
 */
data class ReorderLine(
    val product: Product,
    val onHand: QuantityMilli,
    val suggestion: ReorderSuggestion,
    val purchaseUnit: ConversionDetails?,
    val unitCost: Money,
) {
    /** Sin nada en stock, o en negativo: va primero en su grupo. */
    val soldOut: Boolean get() = onHand.milli <= 0L
}

/** Los productos de un proveedor habitual; con [supplier] nulo, los que no tienen uno elegible. */
data class ReorderGroup(val supplier: Supplier?, val lines: List<ReorderLine>)

/**
 * La lista de reposición, por proveedor habitual y con `Sin proveedor` al final.
 *
 * [hasRecentSales] dice si algún producto de los que se miran tuvo salidas en la ventana: sin
 * ninguna, el vacío pide ventas; con ventas y sin grupos, no hay nada que reponer.
 */
data class ReorderPlan(val groups: List<ReorderGroup>, val hasRecentSales: Boolean)

/**
 * Arma la lista de reposición sugerida a partir del catálogo, lo que salió por ventas y lo que
 * hay.
 *
 * - Solo entran los productos vigentes con stock propio: ni los que no llevan libro, ni los
 *   combos, ni el principal de unas variantes. Cada variante va sola, con su nombre completo.
 * - Entra un producto si se sugiere comprarle algo, o si está bajo su mínimo aunque no haya
 *   tenido ventas recientes: entonces va con cero, para llenarlo a mano.
 * - Un proveedor archivado no se elige, así que sus productos pasan a `Sin proveedor`.
 * - En cada grupo, los agotados primero, después los que menos días alcanzan, y al final por
 *   nombre. Los grupos van por nombre del proveedor.
 *
 * No escribe nada ni crea ninguna compra.
 */
object ReorderPlanner {
    /**
     * La lista para [query], con [products] vigentes, [suppliers] vigentes, [unitsOut] por id de
     * producto y lo que hay en [onHandAt] por id, o en el producto si es `null`. [conversions] son
     * las presentaciones de cada producto por id.
     */
    fun plan(
        query: ReorderQuery,
        products: List<Product>,
        suppliers: List<Supplier>,
        unitsOut: Map<Long, QuantityMilli>,
        onHandAt: Map<Long, QuantityMilli>?,
        conversions: Map<Long, List<ConversionDetails>>,
    ): ReorderPlan {
        val active = suppliers.filterNot { it.isArchived }.associateBy { it.id }
        val parentIds = products.mapNotNullTo(HashSet()) { it.details.parentProductId }
        val inScope = products.filter { product ->
            !product.isArchived &&
                product.details.stockMode == ProductStockMode.TRACKED &&
                product.id !in parentIds &&
                (query.categoryId == null || product.details.categoryId == query.categoryId)
        }
        val lines = inScope.mapNotNull { product ->
            val onHand = if (onHandAt == null) {
                product.onHand
            } else {
                onHandAt[product.id] ?: QuantityMilli.ZERO
            }
            lineOf(query, product, onHand, unitsOut, conversions)
        }
        val bySupplier = lines
            .groupBy { line -> line.product.details.defaultSupplierId?.let(active::get) }
            .filterKeys { supplier -> matches(query.supplier, supplier) }
        // Dos proveedores vigentes nunca comparten nombre: el orden por nombre basta.
        val named = bySupplier.entries
            .mapNotNull { (supplier, members) -> supplier?.let { it to members } }
            .sortedBy { (supplier, _) -> supplier.details.normalizedName }
            .map { (supplier, members) -> ReorderGroup(supplier, members.sortedWith(LINE_ORDER)) }
        val without = bySupplier[null]?.let { ReorderGroup(null, it.sortedWith(LINE_ORDER)) }
        val groups = named + listOfNotNull(without)
        val sold = inScope.any { (unitsOut[it.id]?.milli ?: 0L) > 0L }
        return ReorderPlan(groups, sold)
    }

    /**
     * La unidad en que conviene comprar: entre las presentaciones de compra, la más chica, para
     * que redondear a cajas compre lo menos posible de más.
     */
    fun purchaseUnitOf(conversions: List<ConversionDetails>): ConversionDetails? = conversions
        .filter { it.forPurchase && it.factorMilli > 0L }
        .minWithOrNull(compareBy<ConversionDetails> { it.factorMilli }.thenBy { it.unitId })

    /**
     * Cuánto costaría comprar cada cantidad a su costo por unidad base, o `null` si la suma no
     * cabe en `Long`. Lo que va en cero no suma.
     */
    fun estimatedCost(items: List<Pair<Money, QuantityMilli>>): Money? {
        var total = 0L
        for ((unitCost, quantity) in items) {
            val line = mulDivRound(unitCost.minor, quantity.milli, Scale.MILLI)
            if (line !is Checked.Value) return null
            total = try {
                Math.addExact(total, line.value)
            } catch (_: ArithmeticException) {
                return null
            }
        }
        return Money(total)
    }

    private fun lineOf(
        query: ReorderQuery,
        product: Product,
        onHand: QuantityMilli,
        unitsOut: Map<Long, QuantityMilli>,
        conversions: Map<Long, List<ConversionDetails>>,
    ): ReorderLine? {
        val unit = purchaseUnitOf(conversions[product.id].orEmpty())
        val suggestion = ReorderRules.suggest(
            unitsOut = unitsOut[product.id] ?: QuantityMilli.ZERO,
            windowDays = query.windowDays,
            coverDays = query.coverDays,
            onHand = onHand,
            minStock = product.details.minStock,
            purchaseFactor = unit?.let { QuantityMilli(it.factorMilli) },
        )
        val belowMinimum = onHand < product.details.minStock
        if (suggestion.suggested.milli == 0L && !belowMinimum) return null
        val cost = product.replacementCost.takeIf { it > Money.ZERO } ?: product.averageCost
        return ReorderLine(product, onHand, suggestion, unit, cost)
    }

    private fun matches(filter: ReorderSupplierFilter, supplier: Supplier?): Boolean =
        when (filter) {
            ReorderSupplierFilter.All -> true
            ReorderSupplierFilter.Without -> supplier == null
            is ReorderSupplierFilter.Of -> supplier?.id == filter.supplierId
        }

    private val LINE_ORDER: Comparator<ReorderLine> =
        compareByDescending<ReorderLine> { it.soldOut }
            .thenBy { it.suggestion.daysLeft == null }
            .thenBy { it.suggestion.daysLeft }
            .thenBy { it.product.details.normalizedName }
            .thenBy { it.product.id }
}
