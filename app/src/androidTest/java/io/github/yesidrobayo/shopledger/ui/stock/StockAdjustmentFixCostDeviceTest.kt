package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El aviso sin costo del ajuste de stock lleva a corregir el costo: ofrece `Corregir costo`, y
 * tocarlo pide abrir esa hoja. Con costo conocido, el aviso no aparece. El producto es inventado.
 */
@RunWith(AndroidJUnit4::class)
class StockAdjustmentFixCostDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixCost =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(
            R.string.product_fix_cost,
        )

    @Test
    fun theNoCostNoticeOffersToFixTheCost() {
        val actions = mutableListOf<StockAdjustmentAction>()
        compose.setContent {
            PreviewTheme {
                StockAdjustmentScreen(
                    StockAdjustmentUiState(
                        content = StockAdjustmentContent.Ready(
                            previewSubject(average = 0L, value = 0L),
                        ),
                        mode = AdjustmentMode.CHANGE,
                    ),
                    { actions += it },
                )
            }
        }

        compose.onNodeWithText(fixCost).performScrollTo().performClick()

        assertEquals(listOf<StockAdjustmentAction>(StockAdjustmentAction.FixCost), actions)
    }

    @Test
    fun aKnownCostHasNoNoticeToFixIt() {
        compose.setContent {
            PreviewTheme {
                StockAdjustmentScreen(
                    StockAdjustmentUiState(
                        content = StockAdjustmentContent.Ready(previewSubject()),
                        mode = AdjustmentMode.CHANGE,
                    ),
                    {},
                )
            }
        }

        compose.onAllNodes(hasText(fixCost)).assertCountEquals(0)
    }
}
