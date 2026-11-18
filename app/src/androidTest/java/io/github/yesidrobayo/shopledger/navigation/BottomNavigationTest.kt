package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La barra del teléfono sobre la pila real: `Gastos` abre su pestaña y la barra la marca, el botón
 * redondo `Decir` está en cada pestaña principal y abre `Decir`, y `Vender` abre una venta a mano,
 * donde ni la barra ni `Decir` se ven.
 */
@RunWith(AndroidJUnit4::class)
class BottomNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val say = context.getString(R.string.phrase_action_say)
    private val stack = mutableStateListOf<NavKey>(AppNavKey.Home)
    private val navigator = AppNavigator(stack)
    private var said = 0

    private fun text(id: Int): String = context.getString(id)

    /** La barra como la pone la navegación: abajo, y solo donde la pila la muestra. */
    private fun show() {
        compose.setContent {
            PreviewTheme {
                Box(Modifier.fillMaxSize()) {
                    BottomNavigation(
                        visible = navigator.showsBottomBar,
                        navigator = navigator,
                        onSay = { said++ },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Test
    fun theExpensesEntryOpensTheExpensesTabAndTheBarMarksIt() {
        show()

        compose.onNodeWithText(text(R.string.nav_expenses)).performClick()

        assertEquals(listOf(AppNavKey.Home, AppNavKey.ExpensesTab), stack.toList())
        compose.onNodeWithText(text(R.string.nav_expenses)).assertIsSelected()
        compose.onNodeWithText(text(R.string.nav_home)).assertIsNotSelected()
    }

    @Test
    fun theRoundSayButtonOpensSay() {
        show()

        compose.onNodeWithContentDescription(say).performClick()

        assertEquals(1, said)
        // Decir no cambia la pila: la hoja se abre encima de lo visible.
        assertEquals(listOf<NavKey>(AppNavKey.Home), stack.toList())
    }

    @Test
    fun theRoundSayButtonIsOnEveryMainTab() {
        show()

        for (label in listOf(R.string.nav_expenses, R.string.nav_products, R.string.nav_numbers)) {
            compose.onNodeWithText(text(label)).performClick()
            compose.onNodeWithText(text(label)).assertIsSelected()
            compose.onNodeWithContentDescription(say).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.nav_home)).performClick()
        compose.onNodeWithContentDescription(say).assertIsDisplayed()

        // Registros se abre desde Inicio: también lleva Decir, y la barra marca Inicio.
        compose.runOnIdle { navigator.selectTab(TopLevelDestination.RECORDS) }
        compose.onNodeWithContentDescription(say).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_home)).assertIsSelected()
    }

    @Test
    fun sellOpensASaleByHandWithoutTheBarNorSay() {
        show()

        compose.onNodeWithText(text(R.string.nav_sell)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(AppNavKey.Home, AppNavKey.NewSale()), stack.toList())
        compose.onNodeWithContentDescription(say).assertDoesNotExist()
        assertEquals(0, said)
    }
}
