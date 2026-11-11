package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ventas en espera en el teléfono: tocar una fila la retoma, `Renombrar` y `Descartar` también
 * son acciones de TalkBack, una venta sin nombre dice `Sin nombre`, y la lista vacía lleva a vender.
 */
@RunWith(AndroidJUnit4::class)
class HeldSalesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<HeldSalesAction>()
    private val since = Instant.fromEpochMilliseconds(1_793_634_120_000L)

    private fun show(state: HeldSalesUiState) {
        compose.setContent { PreviewTheme { HeldSalesScreen(state, onAction = { actions += it }) } }
    }

    private fun sales() = HeldSalesUiState(
        content = HeldSalesContent.Sales(
            listOf(
                HeldSaleRow(1L, "Mesa 4", 3L, Money(18_500L), since),
                HeldSaleRow(2L, null, 1L, Money(4_200L), since),
            ),
            BusinessTimeZone.of("Etc/GMT+5"),
        ),
    )

    @Test
    fun tappingARowResumesIt() {
        show(sales())

        compose.onNodeWithText("Mesa 4").performClick()

        assertEquals(listOf<HeldSalesAction>(HeldSalesAction.Resume(1L)), actions)
        compose.onNodeWithText(resources.getString(R.string.draft_no_name)).assertIsDisplayed()
    }

    @Test
    fun renameAndDiscardAreAlsoTalkBackActions() {
        show(sales())
        val rename = resources.getString(R.string.action_rename)
        val discard = resources.getString(R.string.draft_discard_action)
        // La primera fila con acciones propias es la de la primera venta, Mesa 4.
        val row = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .onFirst()

        val custom = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread {
            custom.first { it.label == rename }.action()
            custom.first { it.label == discard }.action()
        }

        assertEquals(
            listOf(HeldSalesAction.OpenRename(1L), HeldSalesAction.AskDiscard(1L)),
            actions,
        )
    }

    @Test
    fun theEmptyListLeadsToSell() {
        show(HeldSalesUiState(content = HeldSalesContent.Empty))

        compose.onNodeWithText(resources.getString(R.string.draft_empty_action)).performClick()

        assertTrue(HeldSalesAction.GoSell in actions)
    }
}
