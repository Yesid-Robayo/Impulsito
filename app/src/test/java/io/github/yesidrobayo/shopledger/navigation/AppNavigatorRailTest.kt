package io.github.yesidrobayo.shopledger.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las reglas del riel lateral sobre la misma pila: qué marca, cuándo se ve, y que Negocio y
 * Ajustes nunca se apilen dos veces.
 */
class AppNavigatorRailTest {
    private val stack = mutableListOf<NavKey>(AppNavKey.Home)
    private val navigator = AppNavigator(stack)

    @Test
    fun theRailMarksTheTabOfWhatIsVisible() {
        assertEquals(AppNavKey.Home, navigator.railSection)

        navigator.openFromRail(AppNavKey.ProductList)
        navigator.open(AppNavKey.ProductDetail(7L))

        assertEquals(
            listOf(AppNavKey.Home, AppNavKey.ProductList, AppNavKey.ProductDetail(7L)),
            stack,
        )
        assertEquals(AppNavKey.ProductList, navigator.railSection)
    }

    @Test
    fun aTabFromTheRailReplacesTheTabLikeTheBar() {
        navigator.openFromRail(AppNavKey.SaleHistory)
        navigator.open(AppNavKey.SaleDetail(3L))

        navigator.openFromRail(AppNavKey.Statistics)

        assertEquals(listOf(AppNavKey.Home, AppNavKey.Statistics), stack)
        assertEquals(AppNavKey.Statistics, navigator.railSection)
    }

    @Test
    fun businessAndSettingsOpenOnTopAndAreMarked() {
        navigator.openFromRail(AppNavKey.ProductList)

        navigator.openFromRail(AppNavKey.BusinessHub)
        assertEquals(AppNavKey.BusinessHub, navigator.railSection)

        navigator.openFromRail(AppNavKey.Settings)
        assertEquals(AppNavKey.Settings, navigator.railSection)
        assertEquals(
            listOf(
                AppNavKey.Home,
                AppNavKey.ProductList,
                AppNavKey.BusinessHub,
                AppNavKey.Settings,
            ),
            stack,
        )
    }

    @Test
    fun businessAlreadyOpenIsReturnedToInsteadOfStackedAgain() {
        navigator.openFromRail(AppNavKey.BusinessHub)
        navigator.open(AppNavKey.Units)
        navigator.open(AppNavKey.Settings)

        navigator.openFromRail(AppNavKey.BusinessHub)

        assertEquals(listOf(AppNavKey.Home, AppNavKey.BusinessHub), stack)
        assertEquals(AppNavKey.BusinessHub, navigator.railSection)

        navigator.openFromRail(AppNavKey.BusinessHub)
        assertEquals(listOf(AppNavKey.Home, AppNavKey.BusinessHub), stack)
    }

    @Test
    fun theRailHidesOnlyDuringTheFirstRun() {
        assertTrue(navigator.showsRail)

        navigator.open(AppNavKey.Onboarding)
        assertFalse(navigator.showsRail)

        navigator.back()
        navigator.open(AppNavKey.NewSale())
        assertTrue(navigator.showsRail)
    }
}
