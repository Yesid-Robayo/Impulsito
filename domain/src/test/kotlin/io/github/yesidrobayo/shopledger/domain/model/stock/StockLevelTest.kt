package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Test

class StockLevelTest {
    @Test
    fun `selling two units more than there were leaves the stock negative`() {
        assertEquals(StockLevel.NEGATIVE, StockLevel.of(QuantityMilli(-2000L)))
        assertEquals(StockLevel.NEGATIVE, StockLevel.of(QuantityMilli(-1L)))
    }

    @Test
    fun `exactly zero is out of stock`() {
        assertEquals(StockLevel.OUT_OF_STOCK, StockLevel.of(QuantityMilli.ZERO))
    }

    @Test
    fun `one thousandth is already in stock`() {
        assertEquals(StockLevel.IN_STOCK, StockLevel.of(QuantityMilli(1L)))
    }

    @Test
    fun `the running out list shows negative first then out of stock then the rest`() {
        val levels = listOf(StockLevel.IN_STOCK, StockLevel.NEGATIVE, StockLevel.OUT_OF_STOCK)

        assertEquals(
            listOf(StockLevel.NEGATIVE, StockLevel.OUT_OF_STOCK, StockLevel.IN_STOCK),
            levels.sorted(),
        )
    }
}
