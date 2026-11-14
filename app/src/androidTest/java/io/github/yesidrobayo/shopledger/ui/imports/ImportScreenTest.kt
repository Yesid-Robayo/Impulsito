package io.github.yesidrobayo.shopledger.ui.imports

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Importar datos en el dispositivo: el paso 1 con su error, el paso 2 con la columna sin
 * relacionar y lo que falta, el 3 con sus errores por fila y el botón de importar, y el 4. Con
 * `captureDir`, las capturas de la puerta de calidad en claro y en oscuro. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ImportScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val actions = mutableListOf<ImportAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theFirstStepOffersTheFileAndExplainsARejection() {
        show {
            ImportScreen(
                ImportUiState(
                    fileProblem = ImportFileProblem.Rejected(ImportRejection.ENCODING),
                ),
                { actions += it },
            )
        }

        compose.onNodeWithText(text(R.string.import_error_encoding)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_local_notice)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_type_suppliers)).performClick()
        compose.onNodeWithText(text(R.string.import_choose_file_action)).performClick()
        assertTrue(ImportAction.ChooseFile in actions)
        assertTrue(ImportAction.ChooseTarget(ImportTarget.SUPPLIERS) in actions)
    }

    @Test
    fun theMappingShowsTheUnknownColumnAndWhatIsMissing() {
        show { ImportScreen(previewImportMap().copy(missing = listOf("name")), { actions += it }) }

        val missing = resources.getString(
            R.string.import_error_required,
            text(R.string.csv_header_name),
        )
        compose.onNodeWithText(missing).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_column_unmatched)).assertIsDisplayed()
        compose.onNodeWithText("Precio venta").performClick()
        assertTrue(ImportAction.OpenColumn(1) in actions)
        compose.onNodeWithText(text(R.string.import_review_action)).performClick()
        assertTrue(ImportAction.Review in actions)
    }

    @Test
    fun theFieldSheetOffersEveryFieldAndSkipping() {
        show { ImportScreen(previewImportMap().copy(choosingColumn = 1), { actions += it }) }

        compose.onNodeWithText(text(R.string.import_column_skip)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.csv_header_sale_price)).performClick()
        assertTrue(ImportAction.ChooseField(1, "sale_price") in actions)
    }

    @Test
    fun theReviewListsEachRowWithItsReasonAndImports() {
        show { ImportScreen(previewImportReview(), { actions += it }) }

        val row = resources.getString(
            R.string.import_issue_row,
            17,
            text(R.string.csv_header_sale_price),
        )
        compose.onNodeWithText(row, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_if_exists_update)).performClick()
        val apply = resources.getQuantityString(R.plurals.import_apply_action, 2, 2)
        compose.onNodeWithText(apply).performClick()
        assertTrue(ImportAction.Apply in actions)
    }

    @Test
    fun nothingToImportDisablesTheButtonWithItsReason() {
        val review = previewImportReview()
        val preview = review.preview?.copy(ready = emptyList(), existing = emptyList())
        show { ImportScreen(review.copy(preview = preview), { actions += it }) }

        compose.onNodeWithText(text(R.string.import_apply_disabled_reason)).assertIsDisplayed()
        val apply = resources.getQuantityString(R.plurals.import_apply_action, 0, 0)
        compose.onNodeWithText(apply).assertIsNotEnabled()
    }

    @Test
    fun theLastStepSaysWhatHappened() {
        show {
            ImportScreen(
                ImportUiState(step = ImportStep.DONE, done = ImportDoneUi(406, 18, 3, 6)),
                { actions += it },
            )
        }

        compose.onNodeWithText(resources.getString(R.string.import_done_created, 406))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_another_action)).performClick()
        assertTrue(ImportAction.ImportAnother in actions)
    }

    @Test
    fun drawsImportInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            show(dark) { ImportScreen(previewImportMap(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-import-map.png"))
            show(dark) { ImportScreen(previewImportReview(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-import-review.png"))
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
