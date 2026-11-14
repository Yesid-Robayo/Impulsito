package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.EARLIER
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crear variantes de un producto, y archivar y reactivar un producto con variantes. */
class ProductVariantsUseCasesTest {
    private val fixture = CatalogFixture()
    private val sizeAndColor = VariantAxes("Talla", "Color")

    @Test
    fun `each variant is a product with its own ledger, cost and price history`() = runTest {
        fixture.seedProduct(id = 1L, name = "Camiseta", defaultSupplierId = 41L)

        val result = fixture.createVariants(
            1L,
            sizeAndColor,
            listOf(
                VariantDraft(
                    VariantValues("S", "Rojo"),
                    salePrice = Money(25_000L),
                    cost = Money(14_000L),
                    initialStock = QuantityMilli(4_000L),
                    minStock = QuantityMilli(2_000L),
                    barcode = "7701",
                ),
                VariantDraft(
                    VariantValues("M", "Rojo"),
                    salePrice = Money(26_000L),
                    cost = Money(15_000L),
                    initialStock = QuantityMilli(6_000L),
                ),
            ),
        )

        assertEquals(CreateVariantsResult.Created(listOf(2L, 3L)), result)
        val small = fixture.product(2L)
        assertEquals("Camiseta · S · Rojo", small.details.name)
        assertEquals(1L, small.details.parentProductId)
        assertEquals("Talla", small.details.variantAxis1)
        assertEquals("Color", small.details.variantAxis2)
        assertEquals("S", small.details.variantValue1)
        assertEquals("Rojo", small.details.variantValue2)
        assertEquals(41L, small.details.defaultSupplierId)
        assertEquals(UNIT_ID, small.details.baseUnitId)
        assertEquals("7701", small.details.barcode)
        assertEquals(Money(25_000L), small.salePrice)
        // Cada una con su libro: 4 a 14.000 son 56.000, y 6 a 15.000 son 90.000.
        assertEquals(QuantityMilli(4_000L), small.onHand)
        assertEquals(Money(14_000L), small.averageCost)
        assertEquals(Money(56_000L), small.stockValue)
        val medium = fixture.product(3L)
        assertEquals(QuantityMilli(6_000L), medium.onHand)
        assertEquals(Money(15_000L), medium.averageCost)
        assertEquals(Money(90_000L), medium.stockValue)
        assertEquals(
            listOf(StockMovementType.OPENING_BALANCE),
            fixture.movementsOf(2L).map { it.movement.type },
        )
        assertEquals(1, fixture.movementsOf(3L).size)
        val history = fixture.tables.priceChanges.values.map { it.content }
        assertEquals(listOf(2L, 3L), history.map { it.productId })
        assertTrue(history.all { it.source == PriceChangeSource.INITIAL })
        // El principal no cambia: sigue sin stock propio.
        assertEquals(QuantityMilli.ZERO, fixture.product(1L).onHand)
        assertNull(fixture.product(1L).details.parentProductId)
        assertEquals(listOf(2L, 3L), fixture.variants.findVariants(1L).map { it.id })
        assertEquals(listOf(2L, 3L), fixture.variants.observeVariants(1L).first().map { it.id })
    }

    @Test
    fun `a variant of a product without stock carries a fixed cost and no ledger`() = runTest {
        fixture.seedProduct(id = 1L, name = "Corte", stockMode = ProductStockMode.UNTRACKED)

        val result = fixture.createVariants(
            1L,
            VariantAxes("Largo", null),
            listOf(VariantDraft(VariantValues("Corto", null), cost = Money(300L))),
        )

        assertEquals(CreateVariantsResult.Created(listOf(2L)), result)
        val short = fixture.product(2L)
        assertEquals(ProductStockMode.UNTRACKED, short.details.stockMode)
        assertEquals(Money(300L), short.details.fixedUnitCost)
        assertEquals(Money.ZERO, short.initialCost)
        assertTrue(fixture.movementsOf(2L).isEmpty())
        assertTrue(fixture.tables.alerts.isEmpty())
    }

    @Test
    fun `the parent stops being watched and a low variant gets a silent alert`() = runTest {
        fixture.create(ProductForm(name = "Camiseta"))
        assertEquals(1, fixture.activeAlertsOf(1L).size)

        fixture.createVariants(
            1L,
            VariantAxes("Talla", null),
            listOf(
                VariantDraft(VariantValues("S", null), minStock = QuantityMilli(1_000L)),
                VariantDraft(
                    VariantValues("M", null),
                    initialStock = QuantityMilli(5_000L),
                    minStock = QuantityMilli(1_000L),
                ),
            ),
        )

        assertTrue(fixture.activeAlertsOf(1L).isEmpty())
        val alert = fixture.activeAlertsOf(2L).single()
        assertEquals(LowStockNotificationStatus.SUPPRESSED, alert.notificationStatus)
        assertTrue(fixture.activeAlertsOf(3L).isEmpty())
    }

    @Test
    fun `more variants can be added later with the same axes, never a repeated one`() = runTest {
        fixture.seedProduct(id = 1L, name = "Camiseta")
        fixture.createVariants(1L, sizeAndColor, listOf(draft("S", "Rojo")))

        val repeated = fixture.createVariants(1L, sizeAndColor, listOf(draft("s", "rojo")))
        val added = fixture.createVariants(1L, sizeAndColor, listOf(draft("M", "Rojo")))

        assertEquals(
            CreateVariantsResult.Invalid(
                emptySet(),
                mapOf(0 to setOf(VariantIssue.DUPLICATE_COMBINATION)),
                emptyMap(),
            ),
            repeated,
        )
        assertEquals(CreateVariantsResult.Created(listOf(3L)), added)
    }

    @Test
    fun `a parent that cannot have variants writes nothing`() = runTest {
        fixture.seedProduct(id = 1L, name = "Archivado", archived = true)
        fixture.seedProduct(id = 2L, name = "Kit", stockMode = ProductStockMode.COMPONENTS)
        fixture.seedProduct(id = 3L, name = "Con stock", onHand = QuantityMilli(2_000L))
        fixture.seedProduct(id = 4L, name = "Camiseta")
        fixture.createVariants(4L, VariantAxes("Talla", null), listOf(draft("S", null)))
        val before = fixture.tables
        val one = VariantAxes("Talla", null)

        assertEquals(
            CreateVariantsResult.ParentNotFound,
            fixture.createVariants(9L, one, listOf(draft("S", null))),
        )
        assertEquals(
            CreateVariantsResult.ParentArchived,
            fixture.createVariants(1L, one, listOf(draft("S", null))),
        )
        assertEquals(
            CreateVariantsResult.ParentMadeOfComponents,
            fixture.createVariants(2L, one, listOf(draft("S", null))),
        )
        assertEquals(
            CreateVariantsResult.ParentHasStock,
            fixture.createVariants(3L, one, listOf(draft("S", null))),
        )
        assertEquals(
            CreateVariantsResult.ParentIsVariant,
            fixture.createVariants(5L, one, listOf(draft("S", null))),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a name or barcode used by another product is rejected with its index`() = runTest {
        fixture.seedProduct(id = 1L, name = "Camiseta")
        fixture.seedProduct(id = 2L, name = "Camiseta · M", barcode = "999")
        val before = fixture.tables
        val one = VariantAxes("Talla", null)

        assertEquals(
            CreateVariantsResult.DuplicateName(1, "Camiseta · M"),
            fixture.createVariants(1L, one, listOf(draft("S", null), draft("m", null))),
        )
        assertEquals(
            CreateVariantsResult.DuplicateBarcode(0, "Camiseta · M"),
            fixture.createVariants(
                1L,
                one,
                listOf(VariantDraft(VariantValues("L", null), barcode = "999")),
            ),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `money needs a confirmed currency, and none is needed without it`() = runTest {
        val unconfirmed = CatalogFixture(currencyConfirmed = false)
        unconfirmed.seedProduct(id = 1L, name = "Camiseta")
        val one = VariantAxes("Talla", null)

        assertEquals(
            CreateVariantsResult.CurrencyUnconfirmed,
            unconfirmed.createVariants(
                1L,
                one,
                listOf(VariantDraft(VariantValues("S", null), salePrice = Money(10L))),
            ),
        )
        assertEquals(
            CreateVariantsResult.CurrencyUnconfirmed,
            unconfirmed.createVariants(
                1L,
                one,
                listOf(VariantDraft(VariantValues("S", null), cost = Money(10L))),
            ),
        )
        assertEquals(
            CreateVariantsResult.Created(listOf(2L)),
            unconfirmed.createVariants(1L, one, listOf(draft("S", null))),
        )
    }

    @Test
    fun `an unknown zone, a stock value over its limit or a busy base write nothing`() = runTest {
        val lost = CatalogFixture(zoneId = "Nowhere/Unknown")
        lost.seedProduct(id = 1L, name = "Camiseta")
        fixture.seedProduct(id = 1L, name = "Camiseta")
        val one = VariantAxes("Talla", null)
        val huge = VariantDraft(
            VariantValues("S", null),
            cost = Money(1_000_000_000_000L),
            initialStock = QuantityMilli(1_000_000_000L),
        )

        assertEquals(
            CreateVariantsResult.UnknownTimeZone,
            lost.createVariants(1L, one, listOf(draft("S", null))),
        )
        assertEquals(
            CreateVariantsResult.AboveLimit(Limit.DOCUMENT_TOTAL),
            fixture.createVariants(1L, one, listOf(huge)),
        )
        fixture.transactions.restoreInProgress = true
        assertEquals(
            CreateVariantsResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            fixture.createVariants(1L, one, listOf(draft("S", null))),
        )
        assertEquals(1, fixture.tables.products.size)
    }

    @Test
    fun `a failure at each write of creating variants leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedProduct(id = 1L, name = "Camiseta")
            },
        ) {
            createVariants(
                1L,
                sizeAndColor,
                listOf(
                    VariantDraft(
                        VariantValues("S", "Rojo"),
                        salePrice = Money(25_000L),
                        cost = Money(14_000L),
                        initialStock = QuantityMilli(4_000L),
                        minStock = QuantityMilli(5_000L),
                    ),
                    draft("M", "Rojo"),
                ),
            )
        }
    }

    @Test
    fun `archiving the parent archives its active variants at the same instant`() = runTest {
        val family = familyWithThreeVariants()
        fixture.archive(family.archivedFirst)

        assertEquals(ArchiveProductResult.Archived, fixture.archive(1L))

        assertEquals(NOW, fixture.product(1L).archivedAt)
        assertEquals(NOW, fixture.product(2L).archivedAt)
        assertEquals(NOW, fixture.product(4L).archivedAt)
        assertTrue(fixture.activeAlertsOf(2L).isEmpty())
    }

    @Test
    fun `reactivating the parent brings back the variants archived with it`() = runTest {
        val family = familyWithThreeVariants()
        archiveAt(family.archivedFirst, EARLIER)
        fixture.archive(1L)

        assertEquals(ReactivateProductResult.Reactivated, fixture.reactivate(1L))

        assertNull(fixture.product(1L).archivedAt)
        assertNull(fixture.product(2L).archivedAt)
        assertNull(fixture.product(4L).archivedAt)
        assertEquals(EARLIER, fixture.product(family.archivedFirst).archivedAt)
        // Vuelve baja de stock: su alerta nace callada. El principal no se vigila.
        val alert = fixture.activeAlertsOf(2L).single()
        assertEquals(LowStockNotificationStatus.SUPPRESSED, alert.notificationStatus)
        assertTrue(fixture.activeAlertsOf(1L).isEmpty())
    }

    @Test
    fun `a variant comes back alone only while its parent is active`() = runTest {
        val family = familyWithThreeVariants()
        fixture.archive(family.archivedFirst)

        assertEquals(ReactivateProductResult.Reactivated, fixture.reactivate(family.archivedFirst))
        fixture.archive(1L)
        assertEquals(ReactivateProductResult.ParentArchived, fixture.reactivate(2L))
        assertEquals(NOW, fixture.product(2L).archivedAt)
    }

    @Test
    fun `if a variant name was taken meanwhile, none of the family comes back`() = runTest {
        familyWithThreeVariants()
        fixture.archive(1L)
        fixture.create(ProductForm(name = "Camiseta · L"))
        val before = fixture.tables

        assertEquals(ReactivateProductResult.NameTaken("Camiseta · L"), fixture.reactivate(1L))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `editing the minimum of a parent creates no alert for it`() = runTest {
        fixture.seedProduct(id = 1L, name = "Camiseta")
        fixture.createVariants(1L, VariantAxes("Talla", null), listOf(draft("S", null)))
        fixture.database.seed { tables ->
            tables.copy(catalogLinks = mapOf(1L to setOf(CatalogLink.VARIANT_PARENT)))
        }

        val form = ProductForm(name = "Camiseta", minStock = QuantityMilli(3_000L))
        val result = fixture.edit(1L, form)

        assertEquals(SaveProductResult.Saved(1L), result)
        assertTrue(fixture.activeAlertsOf(1L).isEmpty())
    }

    @Test
    fun `a failure at each write of archiving or reactivating a family leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedFamily() },
        ) { archive(1L) }
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedFamily()
                archiveDirectly(listOf(1L, 2L, 3L), EARLIER)
            },
        ) { reactivate(1L) }
    }

    /** Camiseta (1) con S (2, baja de stock), M (3) y L (4). Devuelve el id de M. */
    private suspend fun familyWithThreeVariants(): Family {
        fixture.seedProduct(id = 1L, name = "Camiseta")
        fixture.createVariants(
            1L,
            VariantAxes("Talla", null),
            listOf(
                VariantDraft(VariantValues("S", null), minStock = QuantityMilli(1_000L)),
                draft("M", null),
                draft("L", null),
            ),
        )
        return Family(archivedFirst = 3L)
    }

    private fun archiveAt(id: Long, at: Instant) = fixture.archiveDirectly(listOf(id), at)

    private data class Family(val archivedFirst: Long)

    private fun draft(first: String, second: String?) = VariantDraft(VariantValues(first, second))

    private companion object {
        /** Siembra Camiseta (1) con dos variantes, S (2) baja de stock y M (3), ya guardadas. */
        fun CatalogFixture.seedFamily() {
            seedProduct(id = 1L, name = "Camiseta")
            seedProduct(id = 2L, name = "Camiseta · S", minStock = QuantityMilli(1_000L))
            seedProduct(id = 3L, name = "Camiseta · M")
            val values = mapOf(2L to "S", 3L to "M")
            database.seed { tables ->
                tables.copy(
                    products = tables.products.mapValues { (id, product) ->
                        val value = values[id] ?: return@mapValues product
                        product.copy(
                            details = product.details.copy(
                                parentProductId = 1L,
                                variantAxis1 = "Talla",
                                variantValue1 = value,
                            ),
                        )
                    },
                )
            }
        }

        /** Deja [ids] archivados en [at], como en la base, sin pasar por un caso de uso. */
        fun CatalogFixture.archiveDirectly(ids: List<Long>, at: Instant) {
            database.seed { tables ->
                tables.copy(
                    products = tables.products.mapValues { (id, product) ->
                        if (id in ids) product.copy(archivedAt = at) else product
                    },
                )
            }
        }
    }
}
