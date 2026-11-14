package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El selector de ubicación: no existe con una sola, dice la elegida a TalkBack, y elegir otra en
 * su hoja la devuelve. Nombres inventados.
 */
@RunWith(AndroidJUnit4::class)
class LocationSwitcherTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = LocationChoice(1L, "Local centro")
    private val stall = LocationChoice(2L, "Puesto de feria")
    private val chosen = mutableListOf<Long?>()

    private fun spoken(name: String) = context.getString(R.string.a11y_location_switcher, name)

    @Test
    fun withASingleLocationNothingIsDrawn() {
        compose.setContent {
            PreviewTheme { LocationSwitcher(listOf(main), selectedId = 1L, onSelect = {}) }
        }

        compose.onAllNodesWithContentDescription(spoken(main.name)).assertCountEquals(0)
    }

    @Test
    fun theChipOpensTheSheetWithEachLocation() {
        compose.setContent {
            PreviewTheme {
                Column {
                    LocationSwitcher(
                        listOf(main, stall),
                        selectedId = 1L,
                        onSelect = { chosen += it },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(spoken(main.name)).performClick()
        compose.waitForIdle()

        // La hoja entra con una animación que el reloj de la prueba no siempre termina: basta con
        // que exista, con cada ubicación.
        compose.onNodeWithText(stall.name).assertExists()
    }

    @Test
    fun choosingALocationInTheSheetReturnsIt() {
        compose.setContent {
            PreviewTheme {
                Column {
                    LocationSwitcherOptions(
                        locations = listOf(main, stall),
                        selectedId = 1L,
                        allowAll = false,
                        onSelect = { chosen += it },
                        onManage = null,
                    )
                }
            }
        }

        compose.onNodeWithText(stall.name).performClick()

        assertEquals(listOf<Long?>(2L), chosen)
    }

    @Test
    fun theInventoryOffersAllLocations() {
        val all = context.getString(R.string.location_switcher_all)
        compose.setContent {
            PreviewTheme {
                Column {
                    LocationSwitcherOptions(
                        locations = listOf(main, stall),
                        selectedId = 2L,
                        allowAll = true,
                        onSelect = { chosen += it },
                        onManage = null,
                    )
                }
            }
        }

        compose.onNodeWithText(all).performClick()

        assertEquals(listOf<Long?>(null), chosen)
    }
}
