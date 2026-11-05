package io.github.yesidrobayo.shopledger.data.seed

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductComponentEntity
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerEntity
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity
import io.github.yesidrobayo.shopledger.data.local.stock.LocationEntity
import io.github.yesidrobayo.shopledger.data.repository.toStoredMillis
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Crea el negocio inventado antes de su historia: la moneda, las cuentas, los métodos de pago, la
 * segunda ubicación, las categorías, los proveedores, los clientes, las fotos, los productos con
 * su precio inicial y los combos, y el inventario inicial.
 *
 * Cada paso es una transacción del runner, con los repositorios donde ya existen y los DAO donde
 * todavía no: todo pasa por las guardas.
 */
internal class SeedCatalogWriter(
    private val database: AppDatabase,
    private val ports: DataPorts,
    private val stock: SeedStock,
    private val random: SeedRandom,
    private val profile: SeedProfile,
    private val config: SeedConfig,
) {
    private val words = config.language.vocabulary

    suspend fun write(at: Instant, date: BusinessDate): SeedCatalog {
        val setup = ports.transactions.write { TxResult.Commit(setUpBusiness(at)) }
        val expenseCategories =
            database.expenseCategoryDao().observeActive().first().map { it.id }
        val catalog =
            ports.transactions.write {
                TxResult.Commit(createCatalog(setup, expenseCategories, at, date))
            }
        ports.transactions.write {
            openStock(catalog, at, date)
            TxResult.Commit(Unit)
        }
        return catalog
    }

    /** Confirma y bloquea la moneda, y deja listas las cuentas, la ubicación y los métodos. */
    private suspend fun setUpBusiness(at: Instant): Setup {
        val millis = at.toStoredMillis()
        val code = config.currencyCode.code
        val decimals = config.currencyDecimals.toLong()
        val settings = database.appSettingsDao()
        check(settings.updateCurrency(code, decimals, millis) == 1) { "Falta la fila de ajustes" }
        val accounts = database.moneyAccountDao()
        val cash = requireNotNull(accounts.findBySystemKey(CASH_KEY)) { "Falta la caja" }
        check(accounts.setCurrency(cash.id, code, decimals, millis) == 1)
        check(settings.lockCurrency(millis) == 1) { "La moneda ya estaba bloqueada" }
        val bank =
            accounts.insert(
                MoneyAccountEntity(
                    systemKey = null,
                    name = words.bankAccount,
                    normalizedName = TextNormalizer.normalize(words.bankAccount),
                    kind = BANK_KIND,
                    currencyCode = code,
                    currencyDecimals = decimals,
                    balance = 0L,
                    archivedAt = null,
                    createdAt = millis,
                    updatedAt = millis,
                ),
            )
        val locations = database.locationDao()
        val main = requireNotNull(locations.findBySystemKey(MAIN_KEY)) { "Falta la ubicación" }
        val second =
            locations.insert(
                LocationEntity(
                    systemKey = null,
                    name = words.secondLocation,
                    normalizedName = TextNormalizer.normalize(words.secondLocation),
                    kind = WAREHOUSE_KIND,
                    archivedAt = null,
                    createdAt = millis,
                    updatedAt = millis,
                ),
            )
        val priceList =
            requireNotNull(database.priceListDao().findBySystemKey(DEFAULT_KEY)) {
                "Falta la lista de precios"
            }
        return Setup(
            methods = configureMethods(cash.id, bank, millis),
            cashAccountId = cash.id,
            bankAccountId = bank,
            mainLocationId = main.id,
            secondLocationId = second,
            priceListId = priceList.id,
        )
    }

    /**
     * Liga los métodos iniciales a sus cuentas: el efectivo a la caja, y tarjeta, transferencia y
     * billetera al banco. La tarjeta cobra una comisión y acredita en dos días. Son datos que
     * escribiría la persona: ninguna tarifa sale de la app. A cuenta y otro quedan sin cuenta, y
     * sus pagos no mueven dinero.
     */
    private suspend fun configureMethods(
        cash: Long,
        bank: Long,
        millis: Long,
    ): Map<String, SeedPaymentMethod> {
        val dao = database.paymentMethodDao()
        val methods = LinkedHashMap<String, SeedPaymentMethod>()
        for (kind in PaymentMethodKind.entries) {
            val row = requireNotNull(dao.findBySystemKey(kind.name)) { "Falta un método inicial" }
            val (account, feeBp, days) =
                when (kind) {
                    PaymentMethodKind.CASH -> Triple(cash, 0L, 0)

                    PaymentMethodKind.CARD -> Triple(bank, CARD_FEE_BP, CARD_SETTLEMENT_DAYS)

                    PaymentMethodKind.TRANSFER -> Triple(bank, 0L, 0)

                    PaymentMethodKind.WALLET -> Triple(bank, 0L, 1)

                    PaymentMethodKind.ON_ACCOUNT,
                    PaymentMethodKind.OTHER,
                    -> Triple(null, 0L, 0)
                }
            check(
                dao.update(
                    id = row.id,
                    name = null,
                    normalizedName = null,
                    kind = kind.name,
                    feeBp = feeBp,
                    feeFixed = 0L,
                    settlementDays = days.toLong(),
                    priceAdjustmentBp = 0L,
                    moneyAccountId = account,
                    instructionsText = null,
                    instructionsMediaId = null,
                    updatedAt = millis,
                ) == 1,
            )
            val name = requireNotNull(words.paymentMethods[kind.name]) { "Falta un nombre" }
            methods[kind.name] = SeedPaymentMethod(row.id, kind.name, name, account, feeBp, days)
        }
        return methods
    }

    private suspend fun createCatalog(
        setup: Setup,
        expenseCategories: List<Long>,
        at: Instant,
        date: BusinessDate,
    ): SeedCatalog {
        val categories = createCategories(at)
        val suppliers = createSuppliers(at)
        val customers = createCustomers(at)
        val media = createMedia(at)
        val products = createProducts(setup, categories, suppliers, media, at, date)
        val weights = saleWeights(products)
        val heavy = products.firstOrNull { it.id == weights.heavyId }
        return SeedCatalog(
            products = products,
            customers = customers,
            suppliers = suppliers,
            expenseCategoryIds = expenseCategories,
            methods = setup.methods,
            cashAccountId = setup.cashAccountId,
            bankAccountId = setup.bankAccountId,
            mainLocationId = setup.mainLocationId,
            secondLocationId = setup.secondLocationId,
            priceListId = setup.priceListId,
            heavyProduct = heavy,
            saleWeights = weights.byProduct,
            vocabulary = words,
        )
    }

    private suspend fun createCategories(at: Instant): List<Long> = List(profile.categories) {
        val base = words.categories[it % words.categories.size]
        val round = it / words.categories.size
        val name = if (round == 0) base else "$base ${round + 1}"
        ports.categories.insert(
            CategoryDetails(name, TextNormalizer.normalize(name), targetMargin = null),
            at,
        )
    }

    private suspend fun createSuppliers(at: Instant): List<SeedSupplier> = List(profile.suppliers) {
        val prefix = words.supplierPrefixes[it % words.supplierPrefixes.size]
        val place = words.supplierNames[it / words.supplierPrefixes.size % words.supplierNames.size]
        val name = "$prefix $place"
        val terms = random.between(MIN_TERMS_DAYS, MAX_TERMS_DAYS)
        val id =
            ports.suppliers.insert(
                SupplierDetails(
                    name = name,
                    normalizedName = TextNormalizer.normalize(name),
                    phone = null,
                    email = null,
                    notes = null,
                    paymentTermsDays = terms,
                ),
                at,
            )
        SeedSupplier(id, terms)
    }

    /** Clientes con nombre y apellido inventados, sin teléfono ni correo. */
    private suspend fun createCustomers(at: Instant): List<SeedCustomer> {
        val millis = at.toStoredMillis()
        val dao = database.customerDao()
        return List(profile.customers) {
            val first = words.firstNames[it % words.firstNames.size]
            val last = words.lastNames[it / words.firstNames.size % words.lastNames.size]
            val name = "$first $last"
            val id =
                dao.insert(
                    CustomerEntity(
                        name = name,
                        normalizedName = TextNormalizer.normalize(name),
                        phone = null,
                        email = null,
                        notes = null,
                        priceListId = null,
                        balance = 0L,
                        pointsBalance = 0L,
                        anonymizedAt = null,
                        archivedAt = null,
                        createdAt = millis,
                        updatedAt = millis,
                    ),
                )
            val frequent = random.chance(FREQUENT_PER_MILLE)
            SeedCustomer(id, if (frequent) FREQUENT_WEIGHT else 1L)
        }
    }

    /**
     * Las filas de las fotos de producto, con una huella inventada y sin archivo: las fotos nunca
     * entran en la base, y aquí solo cuenta lo que pesa su fila.
     */
    private suspend fun createMedia(at: Instant): List<Long> {
        val dao = database.mediaAssetDao()
        return List(profile.mediaAssets) {
            val sha = buildString { repeat(SHA_WORDS) { append(hex(random.nextLong())) } }
            dao.insert(
                MediaAssetEntity(
                    sha256 = sha,
                    kind = PHOTO_KIND,
                    mimeType = PHOTO_MIME,
                    widthPx = random.between(MIN_PHOTO_SIDE, MAX_PHOTO_SIDE),
                    heightPx = random.between(MIN_PHOTO_SIDE, MAX_PHOTO_SIDE),
                    sizeBytes = random.between(MIN_PHOTO_BYTES, MAX_PHOTO_BYTES),
                    createdAt = at.toStoredMillis(),
                    orphanedAt = null,
                ),
            )
        }
    }

    private suspend fun createProducts(
        setup: Setup,
        categories: List<Long>,
        suppliers: List<SeedSupplier>,
        media: List<Long>,
        at: Instant,
        date: BusinessDate,
    ): List<SeedProduct> {
        val unitIds =
            SeedUnit.entries.associateWith { unit ->
                requireNotNull(database.unitDao().findBySystemKey(unit.systemKey)) {
                    "Falta una unidad inicial"
                }.id
            }
        val trackedCount = profile.products - profile.combos - profile.services
        val names = ProductNames(words)
        val products = ArrayList<SeedProduct>(profile.products)
        // Al menos uno de cada caso que la estrategia de pruebas pide: sin stock, sin reposición
        // y vendido a pérdida. Son los primeros, para que existan también en el perfil chico.
        for (index in 0 until profile.products) {
            val mode =
                when {
                    index < trackedCount -> ProductStockMode.TRACKED
                    index < trackedCount + profile.services -> ProductStockMode.UNTRACKED
                    else -> ProductStockMode.COMPONENTS
                }
            val unit = if (mode == ProductStockMode.TRACKED) pickUnit(index) else SeedUnit.UNIT
            val price = priceFor(unit)
            val atLoss = index == AT_LOSS_INDEX || random.chance(AT_LOSS_PER_MILLE)
            val costBasis =
                when (mode) {
                    ProductStockMode.TRACKED -> costFor(price, atLoss)

                    ProductStockMode.UNTRACKED,
                    ProductStockMode.COMPONENTS,
                    -> 0L
                }
            val fixedCost =
                if (mode == ProductStockMode.UNTRACKED) costFor(price, atLoss = false) else 0L
            val components =
                if (mode == ProductStockMode.COMPONENTS) pickComponents(products) else emptyList()
            val name = names.nameOf(index)
            val categoryId =
                if (random.chance(NO_CATEGORY_PER_MILLE)) null else random.pick(categories)
            val supplierId =
                if (random.chance(SUPPLIER_PER_MILLE)) random.pick(suppliers).id else null
            val minStock = QuantityMilli(minStockFor(mode, unit))
            val withBarcode =
                mode == ProductStockMode.TRACKED && random.chance(BARCODE_PER_MILLE)
            val details =
                ProductDetails(
                    name = name,
                    normalizedName = TextNormalizer.normalize(name),
                    categoryId = categoryId,
                    defaultSupplierId = supplierId,
                    baseUnitId = requireNotNull(unitIds[unit]),
                    stockMode = mode,
                    tracksLots = false,
                    fixedUnitCost = Money(fixedCost),
                    minStock = minStock,
                    barcode = if (withBarcode) barcodeOf(index) else null,
                    parentProductId = null,
                    variantAxis1 = null,
                    variantAxis2 = null,
                    variantValue1 = null,
                    variantValue2 = null,
                    photoMediaId = media.getOrNull(index),
                    taxRateId = null,
                    targetMargin = null,
                    referenceCost = null,
                    referencePrice = null,
                    expiryNoticeDays = null,
                )
            val id =
                ports.products.insert(
                    NewProduct(
                        details = details,
                        salePrice = Money(price),
                        initialCost = Money(costBasis),
                        replacementCost = Money(costBasis),
                        replacementCostAt = if (costBasis > 0L) at else null,
                        createdAt = at,
                    ),
                )
            ports.priceHistory.append(
                NewPriceChange(
                    productId = id,
                    priceListId = setup.priceListId,
                    oldPrice = null,
                    newPrice = Money(price),
                    source = PriceChangeSource.INITIAL,
                    batchId = null,
                    bulkRule = null,
                    occurredAt = at,
                    businessDate = date,
                ),
            )
            for (component in components) {
                database.productComponentDao().insert(
                    ProductComponentEntity(
                        parentProductId = id,
                        componentProductId = component.product.id,
                        quantityMilli = component.quantityMilli,
                        createdAt = at.toStoredMillis(),
                        updatedAt = at.toStoredMillis(),
                    ),
                )
            }
            if (mode == ProductStockMode.TRACKED) stock.register(id, Money(costBasis))
            products +=
                SeedProduct(
                    id = id,
                    name = name,
                    mode = mode,
                    unit = unit,
                    unitId = requireNotNull(unitIds[unit]),
                    salePrice = price,
                    costBasis = costBasis,
                    fixedUnitCost = fixedCost,
                    replacementCost = costBasis,
                    minStockMilli = details.minStock.milli,
                    dual =
                        mode == ProductStockMode.TRACKED && index != 0 &&
                            random.chance(DUAL_PER_MILLE),
                    stocked = mode == ProductStockMode.TRACKED && index != NO_STOCK_INDEX,
                    restocked = index != NEGATIVE_INDEX && !random.chance(NO_RESTOCK_PER_MILLE),
                    components = components,
                )
        }
        return products
    }

    /** Inventario inicial de cada producto con stock, en su ubicación y, si tiene, en la otra. */
    private suspend fun openStock(catalog: SeedCatalog, at: Instant, date: BusinessDate) {
        for (product in catalog.products) {
            if (!product.tracked || !product.stocked) continue
            val locations =
                if (product.dual) {
                    listOf(catalog.mainLocationId, catalog.secondLocationId)
                } else {
                    listOf(catalog.mainLocationId)
                }
            for (location in locations) {
                val quantity =
                    if (product.restocked) {
                        product.unit.roundUp(product.unit.averageSale * OPENING_SALES)
                    } else {
                        product.unit.step
                    }
                val total = checked(Money(product.costBasis).timesQuantity(QuantityMilli(quantity)))
                stock.entry(
                    productId = product.id,
                    locationId = location,
                    type = StockMovementType.OPENING_BALANCE,
                    quantity = QuantityMilli(quantity),
                    total = total,
                    unitCost = Money(product.costBasis),
                    source = StockMovementSource.NONE,
                    at = at,
                    date = date,
                )
            }
        }
    }

    /**
     * Pesos de venta: el 20 % de los productos se lleva cerca del 80 % de las líneas. Combos y
     * servicios reciben la parte de líneas del perfil, y el producto pesado, la suya.
     */
    private fun saleWeights(products: List<SeedProduct>): Weights {
        val weights = LinkedHashMap<Long, Long>()
        val sellable = products.filter { it.tracked && it.stocked }
        for (product in sellable) {
            val popular =
                random.chance(POPULAR_PER_MILLE) || !product.restocked ||
                    product.id == products.first().id
            weights[product.id] =
                if (popular) {
                    random.between(
                        POPULAR_MIN,
                        POPULAR_MAX,
                    )
                } else {
                    random.between(TAIL_MIN, TAIL_MAX)
                }
        }
        var heavyId: Long? = null
        if (profile.heavyProductShareBp > 0) {
            val heavy = sellable.first { it.unit == SeedUnit.UNIT && !it.dual && it.restocked }
            val others = weights.values.sum() - (weights[heavy.id] ?: 0L)
            weights[heavy.id] =
                others * profile.heavyProductShareBp / (BP - profile.heavyProductShareBp)
            heavyId = heavy.id
        }
        val trackedTotal = weights.values.sum()
        val rest = BP - profile.comboLineShareBp - profile.serviceLineShareBp
        val combos = products.filter { it.mode == ProductStockMode.COMPONENTS }
        val services = products.filter { it.mode == ProductStockMode.UNTRACKED }
        val comboWeight = trackedTotal * profile.comboLineShareBp / rest / combos.size
        val serviceWeight = trackedTotal * profile.serviceLineShareBp / rest / services.size
        for (combo in combos) weights[combo.id] = maxOf(1L, comboWeight)
        for (service in services) weights[service.id] = maxOf(1L, serviceWeight)
        return Weights(weights, heavyId)
    }

    /** De dos a cuatro componentes con stock, por unidades, y a veces un servicio. */
    private fun pickComponents(existing: List<SeedProduct>): List<SeedComponent> {
        val candidates = existing.filter { it.tracked && it.stocked && it.unit == SeedUnit.UNIT }
        val count = minOf(candidates.size, random.between(MIN_COMPONENTS, MAX_COMPONENTS))
        val chosen = LinkedHashSet<SeedProduct>()
        while (chosen.size < count) chosen += random.pick(candidates)
        val components =
            chosen.map { SeedComponent(it, random.between(1L, MAX_COMPONENT_UNITS) * UNIT_MILLI) }
        val service = existing.firstOrNull { it.mode == ProductStockMode.UNTRACKED }
        return if (service != null && random.chance(SERVICE_LEAF_PER_MILLE)) {
            components + SeedComponent(service, UNIT_MILLI)
        } else {
            components
        }
    }

    private fun pickUnit(index: Int): SeedUnit {
        if (index == 0) return SeedUnit.UNIT
        val roll = random.nextInt(BP.toInt())
        return when {
            roll < UNIT_SHARE -> SeedUnit.UNIT
            roll < KILOGRAM_SHARE -> SeedUnit.KILOGRAM
            roll < GRAM_SHARE -> SeedUnit.GRAM
            roll < LITER_SHARE -> SeedUnit.LITER
            else -> SeedUnit.MILLILITER
        }
    }

    /**
     * Precio por unidad base, de 500 a 2.000.000 en unidad menor, en cualquier moneda. Con escala
     * logarítmica: muchos productos baratos y pocos caros. Gramos y mililitros, de 500 a 2.000.
     */
    private fun priceFor(unit: SeedUnit): Long {
        if (unit == SeedUnit.GRAM || unit == SeedUnit.MILLILITER) {
            return random.between(MIN_PRICE, SMALL_UNIT_MAX_PRICE) / PRICE_STEP * PRICE_STEP
        }
        var price = MIN_PRICE
        val doublings = random.nextInt(MAX_DOUBLINGS)
        repeat(doublings) { price *= 2 }
        price += random.between(0L, price)
        return minOf(MAX_PRICE, price) / PRICE_STEP * PRICE_STEP
    }

    /** Lo que cuesta comprarlo: entre el 55 y el 80 % del precio, o más que el precio. */
    private fun costFor(price: Long, atLoss: Boolean): Long {
        val percent =
            if (atLoss) {
                random.between(
                    LOSS_MIN_PERCENT,
                    LOSS_MAX_PERCENT,
                )
            } else {
                random.between(COST_MIN_PERCENT, COST_MAX_PERCENT)
            }
        return maxOf(1L, price * percent / PERCENT)
    }

    private fun minStockFor(mode: ProductStockMode, unit: SeedUnit): Long = if (mode ==
        ProductStockMode.TRACKED
    ) {
        random.between(0L, MAX_MIN_STOCK_STEPS) * unit.step
    } else {
        0L
    }

    /** Un código interno de tienda, del rango que no pertenece a ningún país. */
    private fun barcodeOf(index: Int): String = BARCODE_PREFIX + (BARCODE_BASE + index).toString()

    private fun hex(value: Long): String {
        val digits = java.lang.Long.toHexString(value)
        return "0".repeat(HEX_DIGITS - digits.length) + digits
    }

    private class Setup(
        val methods: Map<String, SeedPaymentMethod>,
        val cashAccountId: Long,
        val bankAccountId: Long,
        val mainLocationId: Long,
        val secondLocationId: Long,
        val priceListId: Long,
    )

    private class Weights(val byProduct: Map<Long, Long>, val heavyId: Long?)

    private companion object {
        const val CASH_KEY = "CASH"
        const val MAIN_KEY = "MAIN"
        const val DEFAULT_KEY = "DEFAULT"
        const val BANK_KIND = "BANK"
        const val WAREHOUSE_KIND = "WAREHOUSE"
        const val PHOTO_KIND = "PRODUCT_PHOTO"
        const val PHOTO_MIME = "image/webp"
        const val CARD_FEE_BP = 299L
        const val CARD_SETTLEMENT_DAYS = 2
        const val MIN_TERMS_DAYS = 15
        const val MAX_TERMS_DAYS = 45
        const val FREQUENT_PER_MILLE = 200
        const val FREQUENT_WEIGHT = 16L
        const val SHA_WORDS = 4
        const val HEX_DIGITS = 16
        const val MIN_PHOTO_SIDE = 480L
        const val MAX_PHOTO_SIDE = 1_600L
        const val MIN_PHOTO_BYTES = 30_000L
        const val MAX_PHOTO_BYTES = 400_000L
        const val NO_STOCK_INDEX = 1
        const val NEGATIVE_INDEX = 2
        const val AT_LOSS_INDEX = 3
        const val AT_LOSS_PER_MILLE = 10
        const val NO_RESTOCK_PER_MILLE = 10
        const val NO_CATEGORY_PER_MILLE = 50
        const val SUPPLIER_PER_MILLE = 800
        const val BARCODE_PER_MILLE = 600
        const val DUAL_PER_MILLE = 300
        const val POPULAR_PER_MILLE = 200
        const val POPULAR_MIN = 1_200L
        const val POPULAR_MAX = 2_000L
        const val TAIL_MIN = 60L
        const val TAIL_MAX = 140L
        const val BP = 10_000L
        const val UNIT_SHARE = 7_000
        const val KILOGRAM_SHARE = 8_200
        const val GRAM_SHARE = 8_800
        const val LITER_SHARE = 9_500
        const val MIN_PRICE = 500L
        const val MAX_PRICE = 2_000_000L
        const val SMALL_UNIT_MAX_PRICE = 2_000L
        const val PRICE_STEP = 10L
        const val MAX_DOUBLINGS = 12
        const val PERCENT = 100L
        const val COST_MIN_PERCENT = 55L
        const val COST_MAX_PERCENT = 80L
        const val LOSS_MIN_PERCENT = 105L
        const val LOSS_MAX_PERCENT = 130L
        const val MAX_MIN_STOCK_STEPS = 5L
        const val MIN_COMPONENTS = 2
        const val MAX_COMPONENTS = 4
        const val MAX_COMPONENT_UNITS = 2L
        const val UNIT_MILLI = 1_000L
        const val SERVICE_LEAF_PER_MILLE = 250
        const val OPENING_SALES = 20L
        const val BARCODE_PREFIX = "2"
        const val BARCODE_BASE = 100_000_000_000L
    }
}

/**
 * Los nombres inventados de los productos: un sustantivo, un calificativo y una variedad, sin
 * repetir. Algunos llevan una descripción larga, recortada a los 80 caracteres que admite la base.
 */
private class ProductNames(private val words: SeedVocabulary) {
    private val used = HashSet<String>()

    fun nameOf(index: Int): String {
        val nouns = words.productNouns
        val qualifiers = words.productQualifiers
        val noun = nouns[index % nouns.size]
        val qualifier = qualifiers[index / nouns.size % qualifiers.size]
        val variety = words.productVarieties[
            index / (nouns.size * qualifiers.size) %
                words.productVarieties.size,
        ]
        var name = "$noun $qualifier $variety"
        if (index % LONG_EVERY == LONG_OFFSET) {
            name = "$name ${words.longDescription}".take(MAX_NAME).trimEnd()
        }
        if (!used.add(TextNormalizer.normalize(name))) {
            name = "${name.take(MAX_NAME - SUFFIX_ROOM)} $index"
            check(used.add(TextNormalizer.normalize(name))) { "Dos productos con el mismo nombre" }
        }
        return name
    }

    private companion object {
        const val LONG_EVERY = 37
        const val LONG_OFFSET = 5
        const val MAX_NAME = 80
        const val SUFFIX_ROOM = 8
    }
}
