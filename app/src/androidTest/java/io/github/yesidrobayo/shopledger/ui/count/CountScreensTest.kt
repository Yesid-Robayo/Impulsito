package io.github.yesidrobayo.shopledger.ui.count

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.location.LocationSamples
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El inicio, la sesión y la revisión del conteo físico en el dispositivo: los abiertos se siguen,
 * a ciegas no se ve lo esperado, la revisión avisa lo que se movió y una sesión terminada no se
 * aplica de nuevo. Con `captureDir`, las capturas de la puerta de calidad en claro y en oscuro.
 *
 * Los textos se buscan por recurso, así la prueba vale en el idioma del emulador. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class CountScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val homeActions = mutableListOf<CountHomeAction>()
    private val sessionActions = mutableListOf<CountSessionAction>()

    private fun text(id: Int) = resources.getString(id)

    // Una fila se lee entera por TalkBack: su texto puede estar solo en su descripción.
    private fun count(value: String) =
        compose.onAllNodes(matching(value)).fetchSemanticsNodes().size

    private fun matching(value: String): SemanticsMatcher =
        hasText(value, substring = true) or hasContentDescription(value, substring = true)

    // Con letra al 200 % la lista perezosa no compone lo que queda fuera: se desplaza hasta ahí.
    private fun scrollTo(value: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matching(value))
    }

    private fun home(content: CountHomeContent, sheet: NewCountSheet? = null) =
        CountHomeUiState(content = content, sheet = sheet)

    @Test
    fun theOpenCountsAreFollowedAndThePreviousOnesReadTheirStatus() {
        show {
            CountHomeScreen(
                home(CountHomeContent.Ready(CountSamples.OVERVIEW)),
                { homeActions += it },
            )
        }

        // El primer abierto está arriba: se sigue antes de bajar a los anteriores.
        compose.onAllNodesWithText(text(R.string.count_continue_action))[0].performClick()
        scrollTo(text(R.string.count_status_applied))
        assertEquals(1, count(text(R.string.count_status_applied)))
        scrollTo(text(R.string.count_status_cancelled))
        assertEquals(1, count(text(R.string.count_status_cancelled)))
        compose.onNodeWithText(text(R.string.count_new_action)).performClick()

        assertEquals(
            listOf(CountHomeAction.OpenSession(CountSamples.OPEN.id), CountHomeAction.NewCount),
            homeActions,
        )
    }

    @Test
    fun withoutCountsTheHomeExplainsWhatACountIsFor() {
        show { CountHomeScreen(home(CountHomeContent.Ready(CountSamples.EMPTY)), {}) }

        compose.onNodeWithText(text(R.string.count_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.count_empty_body)).assertIsDisplayed()
    }

    @Test
    fun nothingToCountWithoutACategoryOffersToCreateAProduct() {
        val sheet = NewCountSheet(
            locationId = CountSamples.OPEN.locationId,
            error = NewCountError.SCOPE_EMPTY,
        )
        show {
            CountHomeScreen(
                home(CountHomeContent.Ready(CountSamples.OVERVIEW), sheet),
                { homeActions += it },
            )
        }

        compose.onNodeWithText(text(R.string.count_scope_empty)).assertIsDisplayed()
        // Con dos ubicaciones, la hoja ofrece elegir dónde contar.
        val stall = LocationSamples.STALL.details.name.orEmpty()
        compose.onNode(hasText(stall) and hasClickAction()).performClick()
        assertTrue(CountHomeAction.SheetLocation(LocationSamples.STALL.id) in homeActions)
        compose.onNodeWithText(text(R.string.empty_inventory_action)).performClick()
        assertTrue(CountHomeAction.CreateProduct in homeActions)
    }

    @Test
    fun aBlindCountHidesTheExpectedQuantity() {
        show { CountSessionScreen(CountSamples.counting(blind = true), {}) }
        assertEquals(0, count(text(R.string.count_expected_label)))
        compose.onNodeWithText(text(R.string.count_progress).format(3, 4)).assertIsDisplayed()

        show { CountSessionScreen(CountSamples.counting(blind = false), {}) }
        assertTrue(count(text(R.string.count_expected_label)) > 0)
    }

    @Test
    fun aBlindCountByLotNamesEachLotAndStillHidesTheExpected() {
        val rows = CountSamples.rows()
        val soap = rows.last()
        val byLot = rows.dropLast(1) + listOf(
            soap.copy(lot = CountLotUi("J-1")),
            soap.copy(lineId = 105L, lot = CountLotUi(code = null, unassigned = true)),
        )
        show { CountSessionScreen(CountSamples.counting(blind = true).copy(rows = byLot), {}) }

        val lotName = text(R.string.lot_name).format("J-1")
        scrollTo(lotName)
        assertTrue(count(text(R.string.count_row_lot).format(soap.name, lotName)) >= 1)
        val unassigned = text(R.string.lot_unassigned_name)
        scrollTo(unassigned)
        assertTrue(count(text(R.string.count_row_lot).format(soap.name, unassigned)) >= 1)
        assertEquals(0, count(text(R.string.count_expected_label)))
    }

    @Test
    fun countingGoesToTheReview() {
        show { CountSessionScreen(CountSamples.counting(), { sessionActions += it }) }

        compose.onNodeWithText(text(R.string.count_review_action)).performClick()

        assertEquals(listOf<CountSessionAction>(CountSessionAction.Review), sessionActions)
    }

    @Test
    fun theReviewWarnsWhatMovedAndAppliesTheAdjustments() {
        show { CountSessionScreen(CountSamples.reviewing(), { sessionActions += it }) }

        val moved = resources.getQuantityString(R.plurals.count_moved_notice, 1, 1)
        scrollTo(moved)
        compose.onNodeWithText(moved).assertIsDisplayed()
        scrollTo(text(R.string.count_moved_tag))
        assertEquals(1, count(text(R.string.count_moved_tag)))
        val apply = resources.getQuantityString(R.plurals.count_apply_action, 2, 2)
        compose.onNodeWithText(apply).performClick()

        assertTrue(CountSessionAction.AskApply in sessionActions)
    }

    @Test
    fun anAppliedCountIsOnlyRead() {
        val applied = CountSamples.counting().copy(session = CountSamples.APPLIED)
        show { CountSessionScreen(applied, {}) }

        val apply = resources.getQuantityString(R.plurals.count_apply_action, 2, 2)
        assertEquals(0, count(apply))
        assertEquals(0, count(text(R.string.count_review_action)))
        assertEquals(0, count(text(R.string.count_moved_tag)))
    }

    @Test
    fun drawsTheCountInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        val ready = CountHomeContent.Ready(CountSamples.OVERVIEW)
        val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
            "home" to { CountHomeScreen(home(ready), {}) },
            "empty" to { CountHomeScreen(home(CountHomeContent.Ready(CountSamples.EMPTY)), {}) },
            "sheet" to {
                CountHomeScreen(
                    home(
                        ready,
                        NewCountSheet(
                            locationId = CountSamples.OPEN.locationId,
                            categories = listOf(CountSamples.CATEGORY),
                        ),
                    ),
                    {},
                )
            },
            "blind" to { CountSessionScreen(CountSamples.counting(), {}) },
            "expected" to { CountSessionScreen(CountSamples.counting(blind = false), {}) },
            "review" to { CountSessionScreen(CountSamples.reviewing(), {}) },
            "apply" to {
                CountSessionScreen(CountSamples.reviewing().copy(dialog = CountDialog.APPLY), {})
            },
            "applied" to {
                CountSessionScreen(
                    CountSamples.counting().copy(session = CountSamples.APPLIED),
                    {},
                )
            },
        )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for ((scene, content) in scenes) {
                show(dark, content)
                SystemClock.sleep(SETTLE_MILLIS)
                save(File(dir, "$mode-count-$scene.png"))
            }
        }
    }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(file: File) {
        val bitmap =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
