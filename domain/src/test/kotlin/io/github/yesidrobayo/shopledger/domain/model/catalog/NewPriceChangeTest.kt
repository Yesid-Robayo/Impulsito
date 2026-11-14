package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NewPriceChangeTest {
    private val batch = Uuid.parse("6f1c2a4e-1d2b-4c3a-9e8f-0a1b2c3d4e5f")
    private val rule = BulkPriceRule(BulkPriceAdjustment.Percent(BasisPoints(1_000L)), Money(50L))

    private fun change(
        source: PriceChangeSource,
        oldPrice: Money? = Money(1_000L),
        batchId: Uuid? = null,
        bulkRule: BulkPriceRule? = null,
    ) = NewPriceChange(
        productId = 7L,
        priceListId = 1L,
        oldPrice = oldPrice,
        newPrice = Money(1_100L),
        source = source,
        batchId = batchId,
        bulkRule = bulkRule,
        occurredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        businessDate = BusinessDate(LocalDate(2026, 11, 4)),
    )

    @Test
    fun `every source accepts its own shape`() {
        change(PriceChangeSource.INITIAL, oldPrice = null)
        change(PriceChangeSource.MANUAL)
        change(PriceChangeSource.IMPORT)
        // Un producto que nace de un archivo importado tampoco tiene precio anterior.
        change(PriceChangeSource.IMPORT, oldPrice = null)
        change(PriceChangeSource.BULK, batchId = batch, bulkRule = rule)
        change(PriceChangeSource.BULK_UNDO, batchId = batch)
    }

    @Test
    fun `only the price a product is born with may lack a previous price`() {
        assertThrows(IllegalArgumentException::class.java) {
            change(PriceChangeSource.MANUAL, oldPrice = null)
        }
    }

    @Test
    fun `a batch id belongs to bulk updates and their undo only`() {
        assertThrows(IllegalArgumentException::class.java) {
            change(PriceChangeSource.BULK_UNDO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            change(PriceChangeSource.MANUAL, batchId = batch)
        }
    }

    @Test
    fun `only a bulk update carries its rule`() {
        assertThrows(IllegalArgumentException::class.java) {
            change(PriceChangeSource.BULK, batchId = batch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            change(PriceChangeSource.BULK_UNDO, batchId = batch, bulkRule = rule)
        }
    }

    @Test
    fun `a recorded change keeps its id and content`() {
        val content = change(PriceChangeSource.MANUAL)
        assertEquals(PriceChange(id = 42L, content = content), content.recordedAs(42L))
    }
}
