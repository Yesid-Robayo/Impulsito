package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductUsageTest {
    @Test
    fun `the stored names never change`() {
        // Se guardan por nombre en la base y en la exportación: cambiar uno dejaría filas que
        // ninguna constante lee.
        assertEquals(listOf("SALE", "INPUT", "BOTH"), ProductUsage.entries.map { it.name })
    }

    @Test
    fun `only an input is not sold, and only what is sold alone is not an input`() {
        assertEquals(
            listOf(true, false, true),
            ProductUsage.entries.map { it.isSold },
        )
        assertEquals(
            listOf(false, true, true),
            ProductUsage.entries.map { it.isInput },
        )
    }

    @Test
    fun `a product is sold unless it says otherwise`() {
        val details = ProductDetails(
            name = "Harina",
            normalizedName = "harina",
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

        assertEquals(ProductUsage.SALE, details.usage)
        assertNull(ProductForm(name = "Harina").usage)
    }
}
