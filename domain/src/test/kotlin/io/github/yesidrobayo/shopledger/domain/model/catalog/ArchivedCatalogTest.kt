package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivedCatalogTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val details = ProductDetails(
        name = "Cuaderno",
        normalizedName = "cuaderno",
        categoryId = null,
        defaultSupplierId = null,
        baseUnitId = 1L,
        stockMode = ProductStockMode.TRACKED,
        tracksLots = false,
        fixedUnitCost = Money.ZERO,
        minStock = QuantityMilli.ZERO,
        barcode = null,
        parentProductId = null,
        variantAxis1 = null,
        variantAxis2 = null,
        variantValue1 = null,
        variantValue2 = null,
        photoMediaId = null,
        taxRateId = null,
        targetMargin = null,
        referenceCost = null,
        referencePrice = null,
        expiryNoticeDays = null,
    )

    private fun product(archivedAt: Instant?) = Product(
        id = 1L,
        details = details,
        salePrice = Money(2_500L),
        initialCost = Money.ZERO,
        averageCost = Money.ZERO,
        stockValue = Money.ZERO,
        onHand = QuantityMilli.ZERO,
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = archivedAt,
        createdAt = at,
        updatedAt = at,
    )

    private fun supplier(archivedAt: Instant?) = Supplier(
        id = 1L,
        details = SupplierDetails("Mayorista", "mayorista", null, null, null, null),
        balance = Money.ZERO,
        archivedAt = archivedAt,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `a product is archived only with an archive instant`() {
        assertFalse(product(archivedAt = null).isArchived)
        assertTrue(product(archivedAt = at).isArchived)
    }

    @Test
    fun `a supplier is archived only with an archive instant`() {
        assertFalse(supplier(archivedAt = null).isArchived)
        assertTrue(supplier(archivedAt = at).isArchived)
    }
}
