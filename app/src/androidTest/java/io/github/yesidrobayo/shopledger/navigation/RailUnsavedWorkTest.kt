package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.sale.SaleCartState
import io.github.yesidrobayo.shopledger.ui.sale.leaveSaleTexts
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El riel con una venta en curso: tocar otra sección hace la misma pregunta de salir de la venta.
 * Seguir vendiendo no cambia nada, salir descarta y abre la sección, y sin productos el riel
 * cambia directo.
 */
@RunWith(AndroidJUnit4::class)
class RailUnsavedWorkTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val products = context.getString(R.string.nav_products)
    private val leaveTitle = context.getString(R.string.dialog_leave_sale_title)
    private val keepSelling = context.getString(R.string.dialog_leave_sale_exit)
    private val leave = context.getString(R.string.dialog_leave_sale_action)

    private val stack = mutableStateListOf<NavKey>(AppNavKey.Home, AppNavKey.NewSale())
    private val navigator = AppNavigator(stack)
    private val work = UnsavedWork()

    /** El riel y, debajo, la venta: se marca como lo hace la suya, con tres productos. */
    private fun show(withProducts: Boolean) {
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalUnsavedWork provides work) {
                    MarkUnsavedWork(
                        AppNavKey.NewSale(),
                        withProducts,
                        leaveSaleTexts(SaleCartState(itemCount = 3L), exchange = false),
                    )
                    RailHost(
                        visible = true,
                        navigator = navigator,
                        stack = stack,
                        unsavedWork = work,
                    )
                }
            }
        }
    }

    @Test
    fun keepSellingLeavesTheSaleOpen() {
        show(withProducts = true)

        compose.onNodeWithText(products).performClick()
        compose.onNodeWithText(leaveTitle).assertIsDisplayed()
        assertEquals(listOf(AppNavKey.Home, AppNavKey.NewSale()), stack.toList())

        compose.onNodeWithText(keepSelling).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(leaveTitle).assertDoesNotExist()
        assertEquals(listOf(AppNavKey.Home, AppNavKey.NewSale()), stack.toList())
    }

    @Test
    fun leavingDiscardsTheSaleAndOpensTheSection() {
        show(withProducts = true)

        compose.onNodeWithText(products).performClick()
        compose.onNodeWithText(leave).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(leaveTitle).assertDoesNotExist()
        assertEquals(listOf(AppNavKey.Home, AppNavKey.ProductList), stack.toList())
    }

    @Test
    fun withoutProductsTheRailChangesDirectly() {
        show(withProducts = false)

        compose.onNodeWithText(products).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(leaveTitle).assertDoesNotExist()
        assertEquals(listOf(AppNavKey.Home, AppNavKey.ProductList), stack.toList())
    }
}
