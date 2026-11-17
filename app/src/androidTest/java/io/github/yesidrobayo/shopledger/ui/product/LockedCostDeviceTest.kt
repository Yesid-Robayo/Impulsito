package io.github.yesidrobayo.shopledger.ui.product

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El costo de un producto con movimientos no se escribe al editarlo, porque sale de las compras.
 * Antes el campo quedaba bloqueado sin decir dónde se cambia; ahora dice que cambia con cada compra
 * y ofrece `Corregir costo`, que llega como acción para abrir esa hoja del detalle. Sin movimientos,
 * el costo se escribe y no hay botón. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class LockedCostDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val fixCost = resources.getString(R.string.product_fix_cost)
    private val lockedHelp = resources.getString(R.string.product_cost_locked_help)
    private val actions = mutableListOf<ProductEditAction>()

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun aLockedCostSaysWhereItChangesAndOffersToFixIt() {
        show(state(locked = true))

        compose.onNode(hasText(lockedHelp)).performScrollTo()
        compose.onNode(hasText(fixCost).and(hasClickAction())).performScrollTo().performClick()

        assertEquals(ProductEditAction.FixCost, actions.last())
    }

    @Test
    fun aCostThatCanBeWrittenHasNoFixButton() {
        show(state(locked = false))

        assertEquals(0, count(fixCost))
        assertEquals(0, count(lockedHelp))
    }

    private fun state(locked: Boolean) = ProductEditUiState(
        isNew = false,
        form = ProductEditForm(
            name = "Harina de trigo",
            unitId = COUNT.id,
            salePrice = "5",
            cost = "3",
        ),
        units = listOf(COUNT),
        costLocked = locked,
        averageCost = Money(300L),
    )

    private fun show(state: ProductEditUiState) {
        actions.clear()
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = false) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) {
                        ProductEditScreen(uiState = state, onAction = { actions += it })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    }
}
