package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhrasePrices
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeCategoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeCustomerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeSupplierRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.testing.seedExpenseCategories
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.BOX
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.DOZEN
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.GRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.LITER
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.POUND
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.SACK
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.UNIT
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadPhraseUseCaseTest {
    private val world = PhraseWorld()
    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)),
    )

    /** Los textos de la interfaz, en español, con la coma como separador decimal. */
    private val texts = object : PhraseTextPort {
        override fun language() = PhraseLanguage.ES

        override fun numberStyle() = NumberStyle(decimal = ',', grouping = '.')

        override fun seedName(table: SeedTable, systemKey: String): String =
            SEED_NAMES[systemKey] ?: "${table.name}/$systemKey"

        override fun seedUnitSymbol(systemKey: String): String? = SYMBOLS[systemKey]
    }

    private val read = ReadPhraseUseCase(
        settings = settings,
        texts = texts,
        products = world.products,
        productUnits = world.productUnits,
        components = world.components,
        units = world.units,
        paymentMethods = world.paymentMethods,
        suppliers = FakeSupplierRepository(world.database),
        customers = FakeCustomerRepository(world.database),
        expenseCategories = FakeExpenseCategoryRepository(world.database),
        productCategories = FakeCategoryRepository(world.database),
    )

    init {
        world.addProducts(
            product(BREAD, "Pan", salePrice = 50_000),
            product(
                FLOUR,
                "Harina",
                unitId = KILOGRAM,
                usage = ProductUsage.INPUT,
                replacementCost = 4_000,
                averageCost = 3_500,
            ),
            product(SHIRT, "Camiseta"),
            product(RED_SHIRT, "Camiseta roja", averageCost = 3_000).let {
                it.copy(details = it.details.copy(parentProductId = SHIRT))
            },
            product(
                HAIRCUT,
                "Corte",
                salePrice = 1_000_000_000_000,
                stockMode = ProductStockMode.UNTRACKED,
            ),
            product(OLD, "Antiguo", archived = true),
        )
        world.addConversion(1L, FLOUR, SACK, 50_000, forSale = false)
        world.addConversion(2L, BREAD, BOX, 12_000)
        world.addConversion(3L, RED_SHIRT, DOZEN, 12_000, salePrice = 9_000)
        // Una caja de dos cortes pasaría del tope de un precio: solo se vende con precio escrito.
        world.addConversion(4L, HAIRCUT, BOX, 2_000)
        world.addRecipe(RecipeComponent(1L, BREAD, FLOUR, QuantityMilli(100)))
        world.paymentMethods.seedInitialMethods(PhraseWorld.EPOCH)
        world.addMethods(
            PaymentMethodRecord(
                id = OWN_WALLET,
                systemKey = null,
                details = PaymentMethodDetails(
                    name = "Billetera propia",
                    normalizedName = "billetera propia",
                    kind = PaymentMethodKind.WALLET,
                    feeRate = BasisPoints.ZERO,
                    feeFixed = Money.ZERO,
                    settlementDays = 0,
                    priceAdjustment = BasisPoints.ZERO,
                    moneyAccountId = null,
                    instructionsText = null,
                    instructionsMediaId = null,
                ),
                sortOrder = 99L,
                archivedAt = null,
                createdAt = PhraseWorld.EPOCH,
                updatedAt = PhraseWorld.EPOCH,
            ),
        )
        world.addSuppliers(supplier(RAMIRO, "Don Ramiro"), supplier(GONE, "Ido", archived = true))
        world.addCustomers(
            customer(MARIA, "María"),
            customer(HIDDEN, "Oculta", anonymized = true),
            customer(LEFT, "Se fue", archived = true),
        )
        world.database.seedExpenseCategories(PhraseWorld.EPOCH)
    }

    @Test
    fun `the catalog has the active products as a sale, a purchase and a production see them`() =
        runTest {
            assertEquals(
                listOf(
                    phraseProduct(SHIRT, "Camiseta", forSale = false),
                    phraseProduct(
                        RED_SHIRT,
                        "Camiseta roja",
                        conversions = listOf(PhraseConversion(DOZEN, 12_000)),
                    ),
                    phraseProduct(
                        HAIRCUT,
                        "Corte",
                        conversions = listOf(PhraseConversion(BOX, 2_000)),
                        forPurchase = false,
                        mode = ProductStockMode.UNTRACKED,
                    ),
                    phraseProduct(
                        FLOUR,
                        "Harina",
                        base = KILOGRAM,
                        conversions = listOf(PhraseConversion(SACK, 50_000)),
                        forSale = false,
                    ),
                    phraseProduct(
                        BREAD,
                        "Pan",
                        conversions = listOf(PhraseConversion(BOX, 12_000)),
                        recipe = true,
                    ),
                ),
                read("hola").catalog.products,
            )
        }

    @Test
    fun `units carry their visible names, and the metric ones their measure`() = runTest {
        assertEquals(
            listOf(
                PhraseUnit(UNIT, "unit", "Unidad", null, UnitKind.COUNT, null),
                PhraseUnit(KILOGRAM, "kilogram", "Kilogramo", "kg", UnitKind.MASS, 1_000_000L),
                PhraseUnit(GRAM, "gram", "UNITS/GRAM", "g", UnitKind.MASS, 1_000L),
                PhraseUnit(LITER, "liter", "UNITS/LITER", null, UnitKind.VOLUME, 1_000_000L),
                PhraseUnit(POUND, "pound", "UNITS/POUND", null, UnitKind.MASS, null),
                PhraseUnit(BOX, "box", "UNITS/BOX", null, UnitKind.COUNT, null),
                PhraseUnit(DOZEN, "dozen", "UNITS/DOZEN", null, UnitKind.COUNT, null),
                PhraseUnit(SACK, null, "Bulto", null, UnitKind.COUNT, null),
            ),
            read("hola").catalog.units,
        )
    }

    @Test
    fun `names are the ones the person sees, and only who can be chosen`() = runTest {
        val catalog = read("hola").catalog
        assertEquals(
            listOf(
                PhraseName(1L, "cash", "Efectivo"),
                PhraseName(2L, "card", "PAYMENT_METHODS/CARD"),
                PhraseName(3L, "transfer", "PAYMENT_METHODS/TRANSFER"),
                PhraseName(4L, "wallet", "PAYMENT_METHODS/WALLET"),
                PhraseName(5L, "on_account", "PAYMENT_METHODS/ON_ACCOUNT"),
                PhraseName(6L, "other", "PAYMENT_METHODS/OTHER"),
                PhraseName(OWN_WALLET, null, "Billetera propia"),
            ),
            catalog.paymentMethods,
        )
        assertEquals(listOf(PhraseName(RAMIRO, null, "Don Ramiro")), catalog.suppliers)
        assertEquals(listOf(PhraseName(MARIA, null, "María")), catalog.customers)
        assertEquals(
            PhraseName(1L, "rent", "Arriendo"),
            catalog.expenseCategories.first(),
        )
        assertEquals(
            listOf(
                "rent",
                "utilities",
                "internet_phone",
                "transport",
                "packaging_supplies",
                "advertising",
                "taxes_fees",
                "wages",
                "other",
            ),
            catalog.expenseCategories.map { it.systemKey },
        )
    }

    @Test
    fun `a phrase becomes a card with the number style of the business`() = runTest {
        val result = read("vendí 2 panes a 1.500 en efectivo")
        assertEquals(PhraseIntent.SALE, result.reading.intent)
        assertEquals(
            PhraseDraft(
                intent = PhraseIntent.SALE,
                lines = listOf(
                    DraftLine(
                        DraftTarget.Existing(BREAD),
                        QuantityMilli(2_000),
                        null,
                        unitPrice = Money(150_000),
                    ),
                ),
                paymentMethodId = 1L,
            ),
            result.draft,
        )
    }

    @Test
    fun `prices are the ones the sale uses, and the last cost only a suggestion`() = runTest {
        assertEquals(
            PhrasePrices(
                sale = mapOf(
                    SHIRT to Money.ZERO,
                    RED_SHIRT to Money.ZERO,
                    HAIRCUT to Money(1_000_000_000_000),
                    FLOUR to Money.ZERO,
                    BREAD to Money(50_000),
                ),
                // La caja de 12 panes al precio del pan, y la docena con su precio propio.
                saleByUnit = mapOf(
                    (BREAD to BOX) to Money(600_000),
                    (RED_SHIRT to DOZEN) to Money(9_000),
                ),
                // La reposición manda sobre el promedio.
                lastCost = mapOf(FLOUR to Money(4_000), RED_SHIRT to Money(3_000)),
            ),
            read("hola").prices,
        )
    }

    @Test
    fun `a phrase that says nothing has no card`() = runTest {
        val result = read("hola")
        assertEquals(setOf(PhraseIssue.NOTHING_UNDERSTOOD), result.reading.issues)
        assertNull(result.draft)
    }

    @Test
    fun `a currency with more decimals than the reading knows is not read`() = runTest {
        val current = settings.settings.value
        settings.settings.value = current.copy(
            regional = current.regional.copy(
                currency = current.regional.currency.copy(decimals = 4),
            ),
        )
        val result = read("vendí 2 panes en efectivo")
        assertEquals(
            PhraseReading(intent = null, issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD)),
            result.reading,
        )
        assertNull(result.draft)
    }

    private fun phraseProduct(
        id: Long,
        name: String,
        base: Long = UNIT,
        conversions: List<PhraseConversion> = emptyList(),
        forSale: Boolean = true,
        forPurchase: Boolean = true,
        recipe: Boolean = false,
        mode: ProductStockMode = ProductStockMode.TRACKED,
    ) = PhraseProduct(id, name, base, conversions, forSale, forPurchase, recipe, mode)

    @Test
    fun `product categories are in the catalog, and a verb says the operation`() = runTest {
        world.database.seed { tables ->
            val details = CategoryDetails("Materia prima", "materia prima", targetMargin = null)
            tables.copy(
                categories = mapOf(
                    MATERIALS to Category(MATERIALS, details, PhraseWorld.EPOCH, PhraseWorld.EPOCH),
                ),
            )
        }

        val result = read("compré 2 kilos de arroz, es un insumo, en la categoría materias primas")

        assertEquals(
            listOf(PhraseName(MATERIALS, null, "Materia prima")),
            result.catalog.productCategories,
        )
        assertEquals(true, result.statesOperation)
        val traits = NewProductTraits(usage = ProductUsage.INPUT, categoryId = MATERIALS)
        assertEquals(
            DraftTarget.New("Arroz", traits),
            result.draft?.lines?.single()?.product,
        )
        assertEquals(false, read("la harina es insumo").statesOperation)
    }

    private companion object {
        const val MATERIALS = 77L
        const val BREAD = 100L
        const val FLOUR = 101L
        const val SHIRT = 102L
        const val RED_SHIRT = 103L
        const val HAIRCUT = 104L
        const val OLD = 105L

        const val MARIA = 200L
        const val HIDDEN = 201L
        const val LEFT = 202L

        const val RAMIRO = 300L
        const val GONE = 301L

        const val OWN_WALLET = 450L

        val SEED_NAMES = mapOf(
            "UNIT" to "Unidad",
            "KILOGRAM" to "Kilogramo",
            "CASH" to "Efectivo",
            "RENT" to "Arriendo",
        )
        val SYMBOLS = mapOf("KILOGRAM" to "kg", "GRAM" to "g")
    }
}
