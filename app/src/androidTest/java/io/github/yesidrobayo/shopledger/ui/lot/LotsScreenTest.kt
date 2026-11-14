package io.github.yesidrobayo.shopledger.ui.lot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.ui.A11yAudit
import io.github.yesidrobayo.shopledger.ui.components.LotPicker
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.lotOptionsOf
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de lotes y el selector de lote en el teléfono: cada lote se lee entero, el vencido
 * se nombra con texto, los filtros cambian la lista, la pantalla vacía dice cómo empezar y el
 * selector ofrece primero el automático. Pasa la revisión de accesibilidad con la letra normal y al
 * 200 %, en un teléfono de 360 dp.
 *
 * Los textos se buscan por recurso, así la prueba vale en el idioma del emulador. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class LotsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // Una fila se lee entera por TalkBack: su texto solo está en su descripción.
    private fun count(text: String) = compose
        .onAllNodes(
            hasText(text, substring = true) or hasContentDescription(text, substring = true),
        ).fetchSemanticsNodes()
        .size

    private fun show(
        state: LotsUiState,
        fontScale: Float = 1f,
        onAction: (LotsAction) -> Unit = {},
    ) {
        compose.setContent {
            PreviewTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    Box(Modifier.requiredSize(PHONE_WIDTH.dp, PHONE_HEIGHT.dp)) {
                        LotsScreen(state, onAction)
                    }
                }
            }
        }
    }

    @Test
    fun theExpiringListReadsEachLotAndItsTiles() {
        show(LotSamples.ready())

        assertEquals(1, count(resources.getString(R.string.lot_name, "A-118")))
        assertEquals(1, count(resources.getString(R.string.lot_name, "A-121")))
        // El vencido no está en `Por vencer`: tiene su propio filtro.
        assertEquals(0, count(resources.getString(R.string.lot_name, "L-0042")))
        assertTrue(count(resources.getString(R.string.lot_expiring_value_label)) >= 1)
        val notice = resources.getQuantityString(R.plurals.lot_notice_row, 30, 30)
        assertEquals(1, count(notice))
    }

    @Test
    fun anExpiredLotSaysSoInTextAndTheFiltersAskForTheirList() {
        val actions = mutableListOf<LotsAction>()
        show(LotSamples.ready(LotFilter.EXPIRED), onAction = { actions += it })

        assertEquals(1, count(resources.getString(R.string.lot_name, "L-0042")))
        val expired = resources.getString(R.string.lot_expired_on, "")
        assertTrue(count(expired.substringBefore(" ")) >= 1)
        compose.onNodeWithText(resources.getString(R.string.lot_filter_unassigned)).performClick()

        assertTrue(LotsAction.SelectFilter(LotFilter.UNASSIGNED) in actions)
    }

    @Test
    fun theUnassignedLotSaysWhyItExists() {
        show(LotSamples.ready(LotFilter.UNASSIGNED))

        assertEquals(1, count(resources.getString(R.string.lot_unassigned_name)))
        assertEquals(1, count(resources.getString(R.string.lot_unassigned_help)))
    }

    @Test
    fun withoutLotsItSaysHowToStart() {
        val actions = mutableListOf<LotsAction>()
        show(LotSamples.ready(overview = LotSamples.EMPTY), onAction = { actions += it })

        assertEquals(1, count(resources.getString(R.string.lot_empty_title)))
        compose.onNodeWithText(resources.getString(R.string.lot_empty_action)).performClick()

        assertEquals(listOf<LotsAction>(LotsAction.OpenProducts), actions)
    }

    @Test
    fun theLotSheetOffersTakingItOutAndCorrectingIt() {
        val actions = mutableListOf<LotsAction>()
        val detail = LotSheet.Detail(LotSamples.SOON.lot.id, LotSamples.MOVEMENTS)
        show(LotSamples.ready(sheet = detail), onAction = { actions += it })

        assertEquals(1, count(resources.getString(R.string.lot_by_location_label)))
        assertEquals(1, count(resources.getString(R.string.lot_fix_code_action)))
        compose.onNodeWithText(resources.getString(R.string.lot_take_out_action)).performClick()

        assertTrue(LotsAction.TakeOut(LotSamples.CHEESE.id, LotSamples.SOON.lot.id) in actions)
    }

    @Test
    fun thePickerOffersAutomaticFirstThenTheLotsInExitOrder() {
        val chosen = mutableListOf<Long?>()
        val choices = LotChoices(
            listOf(
                LotChoice(LotStock(1L, "A-1", null, false, QuantityMilli(2_000L)), null),
                LotChoice(LotStock(2L, "A-2", null, false, QuantityMilli(3_000L)), null),
            ),
            null,
        )
        compose.setContent {
            PreviewTheme {
                LotPicker(
                    options = lotOptionsOf(choices, withUnassigned = false),
                    selectedId = null,
                    unit = UnitLabel("u", allowsFraction = false),
                    onSelect = { chosen += it },
                )
            }
        }

        compose.onNodeWithText(resources.getString(R.string.lot_picker_auto)).performClick()
        val lot = resources.getString(R.string.lot_name, "A-2")
        compose.onNode(hasContentDescription(lot, substring = true)).performClick()

        assertEquals(listOf<Long?>(2L), chosen)
    }

    @Test
    fun everyStatePassesTheAccessibilityReviewAtNormalAndDoubleText() {
        val audit = A11yAudit(compose)
        val scenes = listOf(
            "expiring" to LotSamples.ready(),
            "expired" to LotSamples.ready(LotFilter.EXPIRED),
            "all" to LotSamples.ready(LotFilter.ALL),
            "unassigned" to LotSamples.ready(LotFilter.UNASSIGNED),
            "empty" to LotSamples.ready(overview = LotSamples.EMPTY),
        )
        // Una sola composición: cada escena cambia el estado y la letra.
        var shown by mutableStateOf(scenes.first().second to 1f)
        compose.setContent {
            PreviewTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, shown.second),
                ) {
                    Box(Modifier.requiredSize(PHONE_WIDTH.dp, PHONE_HEIGHT.dp)) {
                        LotsScreen(shown.first, {})
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            for ((name, state) in scenes) {
                shown = state to scale
                audit.assertClean("$name al ${(scale * 100).toInt()} %")
            }
        }
    }

    private companion object {
        /** Un teléfono angosto: 360 dp de ancho. */
        const val PHONE_WIDTH = 360
        const val PHONE_HEIGHT = 780
    }
}
