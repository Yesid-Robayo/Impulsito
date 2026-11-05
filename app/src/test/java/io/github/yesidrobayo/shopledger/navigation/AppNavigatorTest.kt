package io.github.yesidrobayo.shopledger.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las reglas de la pila: Inicio siempre al fondo, atrás desde una pestaña vuelve a Inicio, atrás en
 * Inicio le toca al sistema, y la barra solo se ve en las cuatro raíces.
 */
class AppNavigatorTest {
    private val stack = mutableListOf<NavKey>(AppNavKey.Home)
    private val navigator = AppNavigator(stack)

    @Test
    fun anEmptyStackStartsAtHome() {
        val empty = mutableListOf<NavKey>()
        val fresh = AppNavigator(empty)

        assertEquals(listOf(AppNavKey.Home), empty)
        assertEquals(AppNavKey.Home, fresh.current)
        assertEquals(TopLevelDestination.HOME, fresh.currentTab)
        assertTrue(fresh.showsBottomBar)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aStackThatDoesNotStartAtHomeIsRejected() {
        AppNavigator(mutableListOf(AppNavKey.ProductList))
    }

    @Test
    fun aTabSitsOnTopOfHome() {
        navigator.selectTab(TopLevelDestination.PRODUCTS)

        assertEquals(listOf(AppNavKey.Home, AppNavKey.ProductList), stack)
        assertEquals(TopLevelDestination.PRODUCTS, navigator.currentTab)
        assertTrue(navigator.showsBottomBar)
    }

    @Test
    fun backFromATabReturnsHomeAndBackAtHomeIsLeftToTheSystem() {
        navigator.selectTab(TopLevelDestination.NUMBERS)

        assertTrue(navigator.back())
        assertEquals(listOf(AppNavKey.Home), stack)
        assertFalse(navigator.back())
        assertEquals(listOf(AppNavKey.Home), stack)
    }

    @Test
    fun switchingTabsReplacesTheTabInsteadOfStackingIt() {
        navigator.selectTab(TopLevelDestination.RECORDS)
        navigator.selectTab(TopLevelDestination.PRODUCTS)
        navigator.selectTab(TopLevelDestination.NUMBERS)

        assertEquals(listOf(AppNavKey.Home, AppNavKey.Statistics), stack)
        navigator.back()
        assertEquals(listOf(AppNavKey.Home), stack)
    }

    @Test
    fun aDetailHidesTheBarAndKeepsItsTab() {
        navigator.selectTab(TopLevelDestination.PRODUCTS)
        navigator.open(AppNavKey.ProductDetail(productId = 7))

        assertEquals(AppNavKey.ProductDetail(productId = 7), navigator.current)
        assertEquals(TopLevelDestination.PRODUCTS, navigator.currentTab)
        assertFalse(navigator.showsBottomBar)
        navigator.back()
        assertEquals(AppNavKey.ProductList, navigator.current)
        assertTrue(navigator.showsBottomBar)
    }

    @Test
    fun touchingTheActiveTabLeavesTheDepthsOfThatTab() {
        navigator.selectTab(TopLevelDestination.PRODUCTS)
        navigator.open(AppNavKey.ProductDetail(productId = 7))
        navigator.open(AppNavKey.StockAdjustment(productId = 7))

        navigator.selectTab(TopLevelDestination.PRODUCTS)

        assertEquals(listOf(AppNavKey.Home, AppNavKey.ProductList), stack)
    }

    @Test
    fun anotherTabFromADetailDropsTheDetail() {
        navigator.selectTab(TopLevelDestination.PRODUCTS)
        navigator.open(AppNavKey.ProductDetail(productId = 7))

        navigator.selectTab(TopLevelDestination.RECORDS)

        assertEquals(listOf(AppNavKey.Home, AppNavKey.SaleHistory), stack)
    }

    @Test
    fun homeFromAnywhereLeavesOnlyHome() {
        navigator.selectTab(TopLevelDestination.RECORDS)
        navigator.open(AppNavKey.SaleDetail(saleId = 3))

        navigator.selectTab(TopLevelDestination.HOME)

        assertEquals(listOf(AppNavKey.Home), stack)
    }

    @Test
    fun sellOpensANewSaleOnTopOfAnyRoot() {
        navigator.selectTab(TopLevelDestination.RECORDS)

        navigator.sell()

        assertEquals(listOf(AppNavKey.Home, AppNavKey.SaleHistory, AppNavKey.NewSale()), stack)
        assertEquals(TopLevelDestination.RECORDS, navigator.currentTab)
        assertFalse(navigator.showsBottomBar)
        navigator.back()
        assertEquals(AppNavKey.SaleHistory, navigator.current)
    }

    @Test
    fun aDoubleTapDoesNotStackTheSameScreenTwice() {
        navigator.sell()
        navigator.sell()

        assertEquals(listOf(AppNavKey.Home, AppNavKey.NewSale()), stack)
    }

    @Test
    fun openingARootIsTheSameAsTouchingItsTab() {
        navigator.open(AppNavKey.Settings)

        navigator.open(AppNavKey.ProductList)

        assertEquals(listOf(AppNavKey.Home, AppNavKey.ProductList), stack)
    }

    @Test
    fun screensOpenedFromHomeBelongToHome() {
        navigator.open(AppNavKey.Settings)
        navigator.open(AppNavKey.Backup)

        assertEquals(TopLevelDestination.HOME, navigator.currentTab)
        assertFalse(navigator.showsBottomBar)
        assertEquals(listOf(AppNavKey.Home, AppNavKey.Settings, AppNavKey.Backup), stack)
    }

    @Test
    fun everyTabRootIsRecognized() {
        for (tab in TopLevelDestination.entries) {
            assertEquals(tab, TopLevelDestination.ofRoot(tab.root))
        }
        assertEquals(null, TopLevelDestination.ofRoot(AppNavKey.NewSale()))
    }
}
