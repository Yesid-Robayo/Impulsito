package io.github.yesidrobayo.shopledger.ui.settings

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.ui.format.BusinessDisplay
import io.github.yesidrobayo.shopledger.ui.format.ProvideBusinessFormats
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: la pantalla de impuestos sobre la base real, de punta a punta. Empieza sin
 * impuesto y sin tasas, pasa a impuesto incluido tras confirmar, crea dos tasas desde la hoja,
 * marca una por defecto, archiva la que está en uso y elimina la que no.
 *
 * Con el argumento `captureDir` guarda capturas de cada estado en la caché de la app, para la
 * puerta de calidad visual. Deja los ajustes de impuestos como estaban y borra sus tasas.
 */
@RunWith(AndroidJUnit4::class)
class TaxSettingsScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(app.cacheDir, name).apply { mkdirs() }
        }
    private lateinit var original: TaxSettings
    private lateinit var originalRates: Set<Long>

    @Before
    fun rememberAndStartClean() = runBlocking {
        val ports = ports()
        original = ports.taxSettings.get()
        originalRates = allRateIds(ports)
        // La prueba parte de un negocio sin impuesto: así recorre todos los estados.
        commit(ports) {
            ports.taxSettings.update(TaxSettings(TaxMode.NONE, null, false), now())
        }
    }

    @After
    fun restore() = runBlocking {
        val ports = ports()
        commit(ports) {
            ports.taxSettings.update(original, now())
            for (id in allRateIds(ports) - originalRates) ports.taxRates.delete(id)
        }
    }

    @Test
    fun aBusinessGoesFromNoTaxToRatesItWroteItself() {
        val settings = runBlocking { ports().settings.get() }
        val display = BusinessDisplay.of(settings)
        // El porcentaje se escribe con el separador del formato del negocio, no el del teléfono.
        val separator =
            businessFormatsOf(
                display.regional,
                Locale.ENGLISH,
            ).percent.inputSymbols.decimalSeparator
        compose.setContent {
            AppTheme(settings.preferences.colorPalette, darkTheme = isSystemInDarkTheme()) {
                ProvideBusinessFormats(display) {
                    TaxSettingsRoute(onBack = {}, unavailable = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.tax_mode_none_help)).assertExists()
        capture("1-no-tax")

        compose.onNodeWithText(text(R.string.tax_mode_included)).performClick()
        compose.onNodeWithText(text(R.string.tax_mode_confirm_title)).assertExists()
        capture("2-confirm-mode")
        compose.onNodeWithText(text(R.string.tax_mode_confirm_action)).performClick()
        compose.waitUntil(WAIT_MS) { taxes().mode == TaxMode.INCLUDED }
        compose.onNodeWithText(text(R.string.tax_missing_rate_warning)).assertExists()
        capture("3-empty")

        compose.onNodeWithText(text(R.string.tax_empty_action)).performClick()
        typeRate(name = "Tasa general", percent = "21", useAsDefault = true)
        capture("4-sheet")
        compose.onNodeWithText(text(R.string.tax_rate_save_action)).performClick()
        compose.waitUntil(WAIT_MS) { taxes().defaultTaxRateId != null }
        awaitSheetClosed()

        compose.onNodeWithText(text(R.string.tax_rate_new_action)).performClick()
        // Un porcentaje sobre 100 se avisa en su campo y no guarda nada. Un decimal de más ni
        // siquiera se puede escribir: el campo lo descarta.
        typeRate(name = "Tasa reducida", percent = "120", useAsDefault = false)
        compose.onNodeWithText(text(R.string.tax_rate_save_action)).performClick()
        compose.onNodeWithText(text(R.string.tax_error_percent_range)).assertExists()
        capture("5-sheet-error")
        assertEquals(1, activeRates().size)
        compose.onNodeWithText("120").performTextReplacement("8${separator}25")
        compose.onNodeWithText(text(R.string.tax_rate_save_action)).performClick()
        compose.waitUntil(WAIT_MS) { activeRates().size == 2 }
        awaitSheetClosed()
        capture("6-rates")

        // La tasa por defecto se lee entera, con su marca.
        val defaultTag = text(R.string.tax_rate_default_tag)
        compose.onAllNodesWithContentDescription(defaultTag, substring = true)
            .fetchSemanticsNodes()
            .single()
        assertEquals(BasisPoints(2_100), activeRates().first { it.second == "Tasa general" }.third)

        // La predeterminada está en uso: solo se archiva. La otra, sin uso, solo se elimina.
        val menus =
            compose.onAllNodesWithContentDescription(text(R.string.a11y_more_options))
        menus[0].performClick()
        capture("7-menu-in-use")
        compose.onNodeWithText(text(R.string.action_archive)).performClick()
        compose.onNodeWithText(text(R.string.tax_rate_archive_confirm_action)).performClick()
        compose.waitUntil(WAIT_MS) { activeRates().size == 1 }
        compose.onNodeWithText(text(R.string.tax_group_archived)).assertExists()
        capture("8-archived")

        compose.onAllNodesWithContentDescription(text(R.string.a11y_more_options))[0].performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.onNodeWithText(text(R.string.tax_rate_delete_confirm_action)).performClick()
        compose.waitUntil(WAIT_MS) { activeRates().isEmpty() }
        // Archivar la predeterminada dejó al negocio sin tasa por defecto, y se avisa.
        compose.onNodeWithText(text(R.string.tax_missing_rate_warning)).assertExists()
        capture("9-after-delete")
    }

    /** La hoja se cierra con una animación: hasta que termina, su velo recibe los toques. */
    private fun awaitSheetClosed() {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(text(R.string.tax_rate_save_action))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun typeRate(name: String, percent: String, useAsDefault: Boolean) {
        compose.onNodeWithText(text(R.string.tax_rate_name)).performTextReplacement(name)
        compose.onNodeWithText(text(R.string.tax_rate_percent)).performTextReplacement(percent)
        if (useAsDefault) compose.onNodeWithText(text(R.string.tax_rate_use_default)).performClick()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val dir = captureDir ?: return
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MS)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun taxes(): TaxSettings = runBlocking { ports().taxSettings.get() }

    /** Las tasas vigentes creadas por esta prueba: id, nombre y tasa. */
    private fun activeRates(): List<Triple<Long, String, BasisPoints>> = runBlocking {
        ports().taxRates.observe(ArchiveFilter.ACTIVE).first()
            .filter { it.id !in originalRates }
            .map { Triple(it.id, it.details.name, it.details.rate) }
    }

    private suspend fun allRateIds(ports: DataPorts): Set<Long> = (
        ports.taxRates.observe(ArchiveFilter.ACTIVE).first() +
            ports.taxRates.observe(ArchiveFilter.ARCHIVED).first()
        )
        .map { it.id }
        .toSet()

    private suspend fun commit(ports: DataPorts, block: suspend () -> Unit) {
        ports.transactions.write {
            block()
            TxResult.Commit(Unit)
        }
    }

    private fun now() = app.container.clock.now()

    private suspend fun ports(): DataPorts = (app.container.data.open() as DataAccess.Ready).ports

    private companion object {
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 700L
    }
}
