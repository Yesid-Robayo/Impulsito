package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing

/**
 * Arma el contenido de la lista de productos a partir del catálogo y de lo que se busca, con la
 * búsqueda de `ProductSearch`. Es puro: no lee nada, así que se prueba sin base, y se calcula fuera
 * del hilo principal.
 */
object ProductListItems {
    /** Con más productos que esto, la lista lleva encabezados por inicial. */
    const val HEADERS_FROM = 51

    /**
     * El contenido para [products], que llegan en el orden de su nombre normalizado, con
     * [filters] aplicados antes que la búsqueda. [units] trae todas las unidades, también las
     * archivadas, por id. [tags] trae, por producto, el nombre normalizado de cada etiqueta que
     * lleva: la búsqueda también encuentra por ellas.
     *
     * Por nombre, con más de 50 productos, la lista va por inicial. Por stock o por margen va en
     * una sola sección, porque la inicial ya no ayuda a encontrar; los empates conservan el orden
     * por nombre.
     */
    fun build(
        products: List<Product>,
        units: Map<Long, MeasureUnit>,
        query: String,
        filters: ProductListFilters = ProductListFilters(),
        tags: Map<Long, Collection<String>> = emptyMap(),
    ): ProductListContent {
        val narrowed = products.filter(filters::matches)
        if (narrowed.isEmpty()) {
            val filtered = filters.activeCount > 0 || products.isNotEmpty()
            return if (filtered) ProductListContent.FilteredOut else ProductListContent.EmptyCatalog
        }
        val matches = ProductSearch.filter(narrowed, query, tags)
        if (matches.isEmpty()) return ProductListContent.NoResults(query.trim())
        val rows = matches.map { it to itemOf(it, units[it.details.baseUnitId]) }
        val sections = when (filters.sort) {
            ProductSort.NAME -> byInitial(rows)

            ProductSort.STOCK ->
                listOf(ProductListSection(null, rows.sortedWith(STOCK_ORDER).map { it.second }))

            ProductSort.MARGIN ->
                listOf(ProductListSection(null, rows.sortedWith(MARGIN_ORDER).map { it.second }))
        }
        return ProductListContent.Products(sections, query, filters)
    }

    /** Por nombre: una sola sección si son pocos, y por inicial si son muchos. */
    private fun byInitial(rows: List<Pair<Product, ProductListItem>>): List<ProductListSection> {
        if (rows.size <
            HEADERS_FROM
        ) {
            return listOf(ProductListSection(null, rows.map { it.second }))
        }
        return rows
            .groupBy({ (product, _) -> initialOf(product.details.normalizedName) }, { it.second })
            .map { (initial, grouped) -> ProductListSection(initial, grouped) }
    }

    /** Lo que lleva stock, de menos a más; un servicio o uno por componentes, al final. */
    private val STOCK_ORDER: Comparator<Pair<Product, ProductListItem>> =
        compareBy<Pair<Product, ProductListItem>> { (product, _) ->
            product.details.stockMode != ProductStockMode.TRACKED
        }.thenBy { (product, _) -> product.onHand.milli }

    /**
     * De menos a más margen. Sin margen definido, al final: sin precio, sin costo conocido o por
     * componentes, porque un margen contra un costo en cero no dice nada.
     */
    private val MARGIN_ORDER: Comparator<Pair<Product, ProductListItem>> =
        compareBy<Pair<Product, ProductListItem>> { (_, item) -> marginBpOf(item) == null }
            .thenBy { (_, item) -> marginBpOf(item) ?: 0L }

    private fun marginBpOf(item: ProductListItem): Long? {
        val trailing = item.trailing
        val margin = trailing.margin as? Margin.Defined ?: return null
        return margin.value.bp.takeIf { trailing.hasCost }
    }

    /** La fila de [product], en su unidad [unit]. */
    fun itemOf(product: Product, unit: MeasureUnit?): ProductListItem = ProductListItem(
        id = product.id,
        name = product.details.name,
        stock = stockOf(product),
        unit = unit,
        trailing = trailingOf(product),
        archived = product.isArchived,
        photoMediaId = product.details.photoMediaId,
    )

    private fun stockOf(product: Product): ProductListStock {
        val details = product.details
        if (details.stockMode != ProductStockMode.TRACKED) return ProductListStock.NoStock
        val onHand = product.onHand
        return when {
            onHand < QuantityMilli.ZERO -> ProductListStock.Negative(onHand)

            onHand == QuantityMilli.ZERO -> ProductListStock.Out

            LowStockEvaluator.isLow(details.stockMode, false, onHand, details.minStock) ->
                ProductListStock.Low(onHand)

            else -> ProductListStock.Normal(onHand)
        }
    }

    /**
     * Precio y margen de la fila. Un precio en cero se muestra como sin precio, con margen sin dato.
     * El margen usa el costo de catálogo exacto, el que deja el valor del stock; sin costo conocido
     * se calcula con cero y la fila lo marca. Un producto hecho de componentes no tiene costo
     * propio: su margen no se calcula aquí.
     */
    internal fun trailingOf(product: Product): ProductRowTrailing.Catalog {
        val price = product.salePrice.takeIf { it > Money.ZERO }
        val details = product.details
        return when (details.stockMode) {
            ProductStockMode.TRACKED -> {
                val cost = MarginCalculator
                    .catalogUnitCost(product.onHand, product.stockValue, product.averageCost)
                    .valueOrNull() ?: product.averageCost
                val hasCost = product.stockValue > Money.ZERO || product.averageCost > Money.ZERO
                ProductRowTrailing.Catalog(price, marginOf(price, cost), hasCost)
            }

            ProductStockMode.UNTRACKED -> {
                val cost = details.fixedUnitCost
                ProductRowTrailing.Catalog(price, marginOf(price, cost), cost > Money.ZERO)
            }

            ProductStockMode.COMPONENTS ->
                ProductRowTrailing.Catalog(price, Margin.NoData, hasCost = true)
        }
    }

    private fun marginOf(price: Money?, cost: Money): Margin =
        if (price == null) Margin.NoData else MarginCalculator.margin(price, cost)

    /** La inicial de un nombre normalizado, en mayúscula. Lo que no es letra va bajo `#`. */
    private fun initialOf(normalizedName: String): String {
        val first = normalizedName.first()
        return if (first.isLetter()) first.uppercase() else "#"
    }
}
