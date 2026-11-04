package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import org.junit.Assert.assertEquals
import org.junit.Test

// Ids de unidades inventados: 1 es la unidad base por defecto y 2 otra cualquiera, como kilogramo.
class ProductHistoryLockTest {
    private val current = ProductDetails(
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

    private val everything =
        current.copy(baseUnitId = 2L, stockMode = ProductStockMode.UNTRACKED, tracksLots = true)

    @Test
    fun `without history nothing is locked`() {
        assertEquals(emptySet<HistoryLockedField>(), ProductHistoryLock.lockedFields(false))
        assertEquals(
            emptySet<HistoryLockedField>(),
            ProductHistoryLock.lockedChanges(current, everything, hasHistory = false),
        )
    }

    @Test
    fun `with history the base unit the stock mode and the lots are locked`() {
        assertEquals(
            setOf(
                HistoryLockedField.BASE_UNIT,
                HistoryLockedField.STOCK_MODE,
                HistoryLockedField.TRACKS_LOTS,
            ),
            ProductHistoryLock.lockedFields(true),
        )
    }

    @Test
    fun `with history changing the base unit is rejected`() {
        // De unidad a kilogramo: las filas ya escritas contaban unidades.
        assertEquals(
            setOf(HistoryLockedField.BASE_UNIT),
            ProductHistoryLock.lockedChanges(current, current.copy(baseUnitId = 2L), true),
        )
    }

    @Test
    fun `with history each locked field is reported on its own`() {
        assertEquals(
            setOf(HistoryLockedField.STOCK_MODE),
            ProductHistoryLock.lockedChanges(
                current,
                current.copy(stockMode = ProductStockMode.COMPONENTS),
                hasHistory = true,
            ),
        )
        assertEquals(
            setOf(HistoryLockedField.TRACKS_LOTS),
            ProductHistoryLock.lockedChanges(current, current.copy(tracksLots = true), true),
        )
        assertEquals(
            HistoryLockedField.entries.toSet(),
            ProductHistoryLock.lockedChanges(current, everything, hasHistory = true),
        )
    }

    @Test
    fun `with history the rest of the product still changes`() {
        val renamed =
            current.copy(
                name = "Harina de trigo",
                normalizedName = "harina de trigo",
                minStock = QuantityMilli(5_000L),
                barcode = "7701",
            )

        assertEquals(
            emptySet<HistoryLockedField>(),
            ProductHistoryLock.lockedChanges(current, renamed, hasHistory = true),
        )
        assertEquals(
            emptySet<HistoryLockedField>(),
            ProductHistoryLock.lockedChanges(current, current, hasHistory = true),
        )
    }
}
