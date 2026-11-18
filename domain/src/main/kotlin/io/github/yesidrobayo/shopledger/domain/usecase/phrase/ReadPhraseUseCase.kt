package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseComponent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhrasePrices
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseInterpreter
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseMeasures
import io.github.yesidrobayo.shopledger.domain.rule.phrase.toDraft
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits
import kotlinx.coroutines.flow.first

/**
 * Lo que dejó leer una frase: [reading], lo que entendió el intérprete; [catalog], el catálogo con
 * que se leyó, que la tarjeta usa para nombrar lo leído y para que una corrección busque en el
 * mismo; [draft], la tarjeta que propone, o `null` si no se entendió ninguna operación;
 * [prices], los precios con que la tarjeta muestra sus cifras; y [statesOperation], si la frase
 * dice con un verbo qué operación es: con una tarjeta a la vista, una frase sin verbo nunca es otra
 * operación y se suma a la tarjeta.
 */
data class PhraseReadResult(
    val reading: PhraseReading,
    val catalog: PhraseCatalog,
    val draft: PhraseDraft?,
    val prices: PhrasePrices = PhrasePrices(),
    val statesOperation: Boolean = false,
)

/**
 * Lee una frase escrita o dictada y propone la tarjeta. No escribe nada: la persona confirma
 * después, con [RegisterPhraseUseCase].
 *
 * Arma el catálogo en el momento, con lo vigente: los productos con su unidad base, sus
 * conversiones y su receta; todas las unidades, porque un producto puede seguir en una que
 * se archivó; los métodos de pago, los proveedores, los clientes que se pueden elegir, las
 * categorías de gasto y las de producto, para la que se diga de un producto nuevo. Cada cosa
 * lleva el nombre que la persona ve, y una fila inicial sin nombre propio lleva el de su clave en
 * el idioma de la interfaz.
 *
 * - Lo que se vende es lo que no es un insumo, salvo el principal de unas variantes, que no se
 *   vende: así "una camiseta" deja elegir entre sus variantes. Lo que se compra es lo que lleva
 *   stock propio, como en la compra de siempre.
 * - Las unidades iniciales métricas llevan su medida, para aceptar 500 gramos de algo que se lleva
 *   por kilo. Las demás solo valen con su conversión.
 * - Los precios son los de la venta de siempre en la lista inicial: el del producto en su unidad
 *   base y el de cada presentación de venta, con su precio propio o el del producto por su factor.
 *   El último costo es la reposición, o el promedio si no la hay, y solo se ofrece en una compra.
 * - El idioma y la forma de los números salen de [texts], y los decimales, de la moneda del
 *   negocio: nunca del teléfono. Una moneda con más decimales de los que lee el intérprete no se
 *   lee, y la frase queda sin entender.
 */
class ReadPhraseUseCase(
    private val settings: AppSettingsRepository,
    private val texts: PhraseTextPort,
    private val products: ProductRepository,
    private val productUnits: ProductUnitRepository,
    private val components: ProductComponentRepository,
    private val units: UnitRepository,
    private val paymentMethods: PaymentMethodRepository,
    private val suppliers: SupplierRepository,
    private val customers: CustomerRepository,
    private val expenseCategories: ExpenseCategoryRepository,
    private val productCategories: CategoryRepository,
) {
    suspend operator fun invoke(text: String): PhraseReadResult {
        val active = products.observe(ArchiveFilter.ACTIVE).first()
        val conversions = productUnits.observeAll().first().groupBy { it.productId }
        val catalog = catalog(active, conversions)
        val decimals = settings.get().regional.currency.decimals
        val language = texts.language()
        val reading = if (decimals in 0..PhraseInterpreter.MAX_MONEY_DECIMALS) {
            PhraseInterpreter.read(text, language, texts.numberStyle(), decimals, catalog)
        } else {
            NOT_UNDERSTOOD
        }
        return PhraseReadResult(
            reading = reading,
            catalog = catalog,
            draft = reading.toDraft(),
            prices = prices(active, conversions),
            statesOperation = PhraseInterpreter.statesOperation(text, language),
        )
    }

    private fun prices(
        active: List<Product>,
        conversions: Map<Long, List<ProductConversion>>,
    ): PhrasePrices {
        val byUnit = HashMap<Pair<Long, Long>, Money>()
        for (product in active) {
            for (conversion in conversions[product.id].orEmpty()) {
                val details = conversion.details
                if (!details.forSale) continue
                // La misma cuenta de la venta, con la lista inicial.
                val line = SaleLineRequest(product.id, ONE, unitId = details.unitId)
                val unit = SaleUnits.of(line, product, mapOf(details.unitId to details))
                val price = (unit as? SaleUnitResult.Resolved)?.unit?.listPrice ?: continue
                byUnit[product.id to details.unitId] = price
            }
        }
        return PhrasePrices(
            sale = active.associate { it.id to it.salePrice },
            saleByUnit = byUnit,
            lastCost = active.mapNotNull { product ->
                val cost = product.replacementCost.takeIf { it > Money.ZERO }
                    ?: product.averageCost.takeIf { it > Money.ZERO }
                cost?.let { product.id to it }
            }.toMap(),
        )
    }

    private suspend fun catalog(
        active: List<Product>,
        conversions: Map<Long, List<ProductConversion>>,
    ): PhraseCatalog {
        val recipes = components.findAll().groupBy { it.parentProductId }
        // El principal de unas variantes es el que nombran sus variantes.
        val parents = active.mapNotNullTo(HashSet()) { it.details.parentProductId }
        return PhraseCatalog(
            products = active.map { product ->
                phraseProduct(product, conversions[product.id].orEmpty(), recipes, parents)
            },
            units = units.observeAll().first().map(::phraseUnit),
            paymentMethods = paymentMethods.findActive().map { method ->
                PhraseName(
                    id = method.id,
                    systemKey = method.systemKey?.lowercase(),
                    name = method.details.name
                        ?: seedName(SeedTable.PAYMENT_METHODS, method.systemKey),
                )
            },
            suppliers = suppliers.observe(ArchiveFilter.ACTIVE).first().map {
                PhraseName(it.id, systemKey = null, name = it.details.name)
            },
            // Un cliente anonimizado no tiene nombre ni se elige: no se ofrece.
            customers = customers.observeSelectable().first().mapNotNull { customer ->
                customer.contact?.let { PhraseName(customer.id, systemKey = null, name = it.name) }
            },
            expenseCategories = expenseCategories.observe(ArchiveFilter.ACTIVE).first().map {
                PhraseName(
                    id = it.id,
                    systemKey = it.systemKey?.lowercase(),
                    name = it.details.name ?: seedName(SeedTable.EXPENSE_CATEGORIES, it.systemKey),
                )
            },
            productCategories = productCategories.observeAll().first().map {
                PhraseName(it.id, systemKey = null, name = it.details.name)
            },
        )
    }

    private fun phraseProduct(
        product: Product,
        conversions: List<ProductConversion>,
        recipes: Map<Long, List<RecipeComponent>>,
        parents: Set<Long>,
    ): PhraseProduct = PhraseProduct(
        id = product.id,
        name = product.details.name,
        baseUnitId = product.details.baseUnitId,
        conversions = conversions.map {
            PhraseConversion(it.details.unitId, it.details.factorMilli)
        },
        forSale = product.details.usage.isSold && product.id !in parents,
        forPurchase = StockModeRules.hasLedger(product.details.stockMode),
        hasRecipe = product.id in recipes,
        stockMode = product.details.stockMode,
        recipe = recipes[product.id].orEmpty().map {
            PhraseComponent(it.componentProductId, it.quantity)
        },
    )

    /**
     * La clave va en minúsculas, como la conocen los vocabularios del intérprete; la medida se
     * busca con la clave tal como se guarda.
     */
    private fun phraseUnit(unit: MeasureUnit): PhraseUnit = PhraseUnit(
        id = unit.id,
        systemKey = unit.systemKey?.lowercase(),
        name = unit.name ?: seedName(SeedTable.UNITS, unit.systemKey),
        symbol = unit.symbol ?: unit.systemKey?.let(texts::seedUnitSymbol),
        kind = unit.kind,
        baseAmountMilli = PhraseMeasures.baseAmountMilli(unit.systemKey),
    )

    /** El nombre de una fila inicial; una fila sin nombre ni clave no se puede nombrar. */
    private fun seedName(table: SeedTable, systemKey: String?): String =
        systemKey?.let { texts.seedName(table, it) }.orEmpty()

    private companion object {
        val ONE = QuantityMilli(1_000L)

        val NOT_UNDERSTOOD = PhraseReading(
            intent = null,
            issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD),
        )
    }
}
