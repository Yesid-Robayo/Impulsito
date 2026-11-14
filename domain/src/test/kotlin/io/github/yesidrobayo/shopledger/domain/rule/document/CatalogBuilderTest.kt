package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogItem
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogSection
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogVariant
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El catálogo compartible: solo productos vigentes con precio en la lista elegida, sin agotados
 * salvo que se pidan, variantes bajo su producto, orden por nombre y por categoría, y nada de
 * costo ni stock en el documento. Las cifras están hechas a mano.
 */
class CatalogBuilderTest {
    private val lists = listOf(
        PriceList(DEFAULT, "DEFAULT", null, PriceListPricing.Fixed, null),
        PriceList(LOOP, null, "Rota", PriceListPricing.Relative(LOOP, BasisPoints(0L)), null),
        PriceList(
            WHOLESALE,
            null,
            "Mayorista",
            PriceListPricing.Relative(DEFAULT, BasisPoints(-1_000L)),
            null,
        ),
    ).associateBy { it.id }
    private val categories = listOf(category(DRINKS, "Bebidas"), category(GROCERY, "Abarrotes"))
    private val products = listOf(
        product(1L, "Arroz 1 kg", 4_200L, onHand = 10_000L, category = GROCERY, photo = 50L),
        product(2L, "Aceite 900 ml", 12_500L, onHand = 0L, category = GROCERY),
        product(3L, "Agua 600 ml", 1_500L, onHand = 3_000L, category = DRINKS),
        product(4L, "Domicilio", 3_000L, mode = ProductStockMode.UNTRACKED),
        product(5L, "Sin precio", 0L, onHand = 1_000L),
        product(6L, "Archivado", 1_000L, onHand = 1_000L, archived = true),
        product(7L, "Camiseta", 0L, category = DRINKS),
        product(8L, "Camiseta roja M", 20_000L, 2_000L, parent = 7L, values = "Roja" to "M"),
        product(9L, "Camiseta azul S", 20_000L, onHand = 0L, parent = 7L, values = "Azul" to null),
        product(10L, "Gorra", 0L),
        product(11L, "Gorra única", 8_000L, onHand = 0L, parent = 10L, values = " " to null),
        product(13L, "Camiseta verde L", 0L, onHand = 1_000L, parent = 7L),
    )
    private val header = ReceiptHeader(5L, "Tienda Central", null)

    @Test
    fun `all priced and in stock products, by name, with variants under their product`() {
        val document = build(options())

        assertEquals(
            listOf(
                CatalogSection(
                    null,
                    listOf(
                        item("Agua 600 ml", 1_500L),
                        item("Arroz 1 kg", 4_200L),
                        CatalogItem(
                            "Camiseta",
                            UNIT,
                            null,
                            null,
                            listOf(CatalogVariant("Roja / M", Money(20_000L))),
                        ),
                        item("Domicilio", 3_000L),
                    ),
                ),
            ),
            document.sections,
        )
        assertEquals(4, document.productCount)
        assertEquals("Lista de verano", document.title)
        assertEquals(header, document.header)
        assertEquals(DATE, document.pricesOn)
    }

    @Test
    fun `out of stock can be included, photos shown and a wholesale list used`() {
        val document = build(
            options(
                includeOutOfStock = true,
                showPhotos = true,
                listId = WHOLESALE,
                title = "  ",
            ),
        )

        val items = document.sections.single().items
        assertEquals(
            listOf("Aceite 900 ml", "Agua 600 ml", "Arroz 1 kg", "Camiseta", "Domicilio", "Gorra"),
            items.map { it.name },
        )
        assertEquals(Money(3_780L), items.single { it.name == "Arroz 1 kg" }.price)
        assertEquals(50L, items.single { it.name == "Arroz 1 kg" }.photoMediaId)
        assertEquals(
            listOf("Azul", "Roja / M"),
            items.single { it.name == "Camiseta" }.variants.map { it.label },
        )
        assertEquals(
            listOf(CatalogVariant("Gorra única", Money(7_200L))),
            items.single { it.name == "Gorra" }.variants,
        )
        assertEquals(7, document.productCount)
        assertNull(document.title)
    }

    @Test
    fun `grouping by category puts each one by name and the ones without category last`() {
        val document = build(options(group = true))

        assertEquals(
            listOf("Abarrotes", "Bebidas", null),
            document.sections.map { it.categoryName },
        )
        assertEquals(listOf("Arroz 1 kg"), document.sections[0].items.map { it.name })
        assertEquals(
            listOf("Agua 600 ml", "Camiseta"),
            document.sections[1].items.map { it.name },
        )
        assertEquals(listOf("Domicilio"), document.sections[2].items.map { it.name })
        val drinks = build(options(group = true, filter = CatalogFilter.InCategory(DRINKS)))
        assertEquals(listOf<String?>("Bebidas"), drinks.sections.map { it.categoryName })
    }

    @Test
    fun `one category only, and none in it leaves the catalog empty`() {
        val drinks = build(options(filter = CatalogFilter.InCategory(DRINKS)))
        val none = build(options(filter = CatalogFilter.InCategory(99L)))

        assertEquals(
            listOf("Agua 600 ml", "Camiseta"),
            drinks.sections.single().items.map { it.name },
        )
        assertTrue(none.sections.isEmpty())
        assertEquals(0, none.productCount)
    }

    @Test
    fun `a list that cannot price anything leaves the catalog empty`() {
        assertTrue(build(options(listId = LOOP, includeOutOfStock = true)).sections.isEmpty())
    }

    @Test
    fun `a product in a category that no longer exists goes with the ones without category`() {
        val orphan = product(12L, "Panela", 2_000L, onHand = 1_000L, category = 77L)
        val document = CatalogBuilder.of(
            listOf(orphan),
            categories,
            options(group = true),
            header,
            DATE,
        )

        assertEquals(listOf<String?>(null), document.sections.map { it.categoryName })
    }

    @Test
    fun `no cost, stock nor supplier reaches the catalog`() {
        val text = build(options(includeOutOfStock = true)).toString()
        val fields =
            Regex("""(\w+)=""").findAll(text).map { it.groupValues[1].lowercase() }.toSet()

        val forbidden = listOf("cost", "margin", "profit", "supplier", "onhand", "stock")
        assertFalse(fields.any { field -> forbidden.any { it in field } })
        assertFalse(text.contains(SENTINEL_COST.toString()))
    }

    @Test
    fun `an input never reaches the catalog, but what is also sold does`() {
        val flour = product(30L, "Harina", 2_000L, onHand = 5_000L).used(ProductUsage.INPUT)
        val sugar = product(31L, "Azúcar", 1_800L, onHand = 5_000L).used(ProductUsage.BOTH)
        val cake = product(32L, "Torta", 0L)
        val small = product(33L, "Torta chica", 9_000L, 1_000L, parent = 32L, values = "S" to null)
        val big = product(34L, "Torta grande", 15_000L, 1_000L, parent = 32L, values = "L" to null)
            .used(ProductUsage.INPUT)

        val document = CatalogBuilder.of(
            listOf(flour, sugar, cake, small, big),
            categories,
            options(),
            header,
            DATE,
        )

        assertEquals(
            listOf(
                item("Azúcar", 1_800L),
                CatalogItem("Torta", UNIT, null, null, listOf(CatalogVariant("S", Money(9_000L)))),
            ),
            document.sections.single().items,
        )
    }

    private fun Product.used(usage: ProductUsage): Product =
        copy(details = details.copy(usage = usage))

    private fun build(options: CatalogOptions) =
        CatalogBuilder.of(products, categories, options, header, DATE)

    private fun options(
        filter: CatalogFilter = CatalogFilter.All,
        listId: Long = DEFAULT,
        showPhotos: Boolean = false,
        group: Boolean = false,
        includeOutOfStock: Boolean = false,
        title: String = " Lista de verano ",
    ) = CatalogOptions(
        filter = filter,
        prices = SalePriceList(listId, lists, emptyMap()),
        showPhotos = showPhotos,
        groupByCategory = group,
        includeOutOfStock = includeOutOfStock,
        title = title,
    )

    private fun item(name: String, price: Long) =
        CatalogItem(name, UNIT, Money(price), null, emptyList())

    private fun category(id: Long, name: String) =
        Category(id, CategoryDetails(name, name.lowercase(), null), AT, AT)

    private fun product(
        id: Long,
        name: String,
        price: Long,
        onHand: Long = 0L,
        mode: ProductStockMode = ProductStockMode.TRACKED,
        category: Long? = null,
        photo: Long? = null,
        archived: Boolean = false,
        parent: Long? = null,
        values: Pair<String?, String?>? = null,
    ): Product = Product(
        id = id,
        details = CatalogFixture.details(name, mode).copy(
            normalizedName = name.lowercase(),
            categoryId = category,
            baseUnitId = UNIT,
            parentProductId = parent,
            photoMediaId = photo,
            variantValue1 = values?.first,
            variantValue2 = values?.second,
        ),
        salePrice = Money(price),
        initialCost = Money(SENTINEL_COST),
        averageCost = Money(SENTINEL_COST),
        stockValue = Money(SENTINEL_COST),
        onHand = QuantityMilli(onHand),
        replacementCost = Money(SENTINEL_COST),
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = if (archived) AT else null,
        createdAt = AT,
        updatedAt = AT,
    )

    private companion object {
        const val DEFAULT = 1L
        const val WHOLESALE = 2L
        const val LOOP = 3L
        const val DRINKS = 20L
        const val GROCERY = 21L
        const val UNIT = 1L
        const val SENTINEL_COST = 777_777L
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val DATE = BusinessDate(LocalDate(2026, 11, 14))
    }
}
