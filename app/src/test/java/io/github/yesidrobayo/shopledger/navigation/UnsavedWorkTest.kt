package io.github.yesidrobayo.shopledger.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las marcas de trabajo sin guardar: qué pregunta toca antes de quitar pantallas de la pila, y que
 * las de pantallas que ya salieron se olviden.
 */
class UnsavedWorkTest {
    private val work = UnsavedWork()
    private val changes = DiscardTexts("changes", "lost", "discard", dismiss = "keep")
    private val sale = DiscardTexts("sale", "3 items", "leave", destructive = true)
    private val stack: List<NavKey> =
        listOf(AppNavKey.Home, AppNavKey.NewSale(), AppNavKey.Checkout, AppNavKey.ProductEdit())

    @Test
    fun withoutMarksNothingIsAsked() {
        assertTrue(work.isEmpty)
        assertNull(work.questionFor(stack, stack.drop(1)))
    }

    @Test
    fun theTopmostDroppedScreenWithUnsavedWorkAsks() {
        work.mark(AppNavKey.NewSale(), sale)
        work.mark(AppNavKey.ProductEdit(), changes)

        assertFalse(work.isEmpty)
        assertEquals(changes, work.questionFor(stack, stack.drop(1)))
        assertEquals(sale, work.questionFor(stack, stack.drop(1).dropLast(1)))
    }

    @Test
    fun aMarkedScreenThatStaysInTheStackDoesNotAsk() {
        work.mark(AppNavKey.NewSale(), sale)

        assertNull(work.questionFor(stack, listOf(AppNavKey.Checkout, AppNavKey.ProductEdit())))
    }

    @Test
    fun clearingTheMarkStopsTheQuestion() {
        work.mark(AppNavKey.NewSale(), sale)
        work.clear(AppNavKey.NewSale())

        assertTrue(work.isEmpty)
        assertNull(work.questionFor(stack, stack.drop(1)))
    }

    @Test
    fun marksOfScreensThatLeftTheStackAreForgotten() {
        work.mark(AppNavKey.Purchase(), changes)

        assertNull(work.questionFor(stack, stack.drop(1)))
        assertTrue(work.isEmpty)
    }

    @Test
    fun discardingForgetsTheDroppedMarksOnly() {
        work.mark(AppNavKey.NewSale(), sale)
        work.mark(AppNavKey.ProductEdit(), changes)

        work.discard(listOf(AppNavKey.ProductEdit()))

        assertEquals(sale, work.questionFor(stack, stack.drop(1)))
    }
}
