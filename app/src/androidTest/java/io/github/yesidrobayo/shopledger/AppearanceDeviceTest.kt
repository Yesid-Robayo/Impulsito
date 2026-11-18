package io.github.yesidrobayo.shopledger

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.settings.AppPreferences
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.ui.theme.Appearance
import io.github.yesidrobayo.shopledger.ui.theme.paletteOf
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: la paleta y el modo guardados pintan la pantalla, y la barra inferior navega.
 *
 * Toca la pantalla como una persona, con eventos del sistema, y mira los píxeles de la captura: el
 * papel en el margen, la barra y la píldora de la pestaña activa. No usa herramientas de interfaz.
 * Con el argumento `captureDir`, además guarda cada captura en la caché de la app, para revisar la
 * calidad visual a ojo.
 *
 * Deja la paleta y el modo como los encontró.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(app.cacheDir, name).apply { mkdirs() }
        }
    private lateinit var original: AppPreferences

    @Before
    fun rememberPreferences() {
        original = runBlocking { ports().settings.get().preferences }
        // La prueba recorre Inicio y su barra: el primer uso, que se abre encima, ya terminó.
        runBlocking {
            val settings = ports().settings
            if (settings.get().onboardingCompletedAt == null) {
                settings.markOnboardingCompleted(app.container.clock.now())
            }
        }
    }

    @After
    fun restorePreferences() {
        runBlocking { write(Appearance(original.colorPalette, original.themeMode)) }
    }

    @Test
    fun theStoredPaletteAndModeColorTheScreenAndTheBarNavigates() {
        for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.VIVID_RED_CREAM)) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val appearance = Appearance(palette, mode)
                runBlocking { write(appearance) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    walkThroughTheBar(scenario, appearance)
                }
            }
        }
    }

    private fun walkThroughTheBar(
        scenario: ActivityScenario<MainActivity>,
        appearance: Appearance,
    ) {
        awaitAppearance(scenario, appearance)
        val set = paletteOf(appearance.palette)
        val dark = appearance.themeMode == ThemeMode.DARK
        val scheme = if (dark) set.darkScheme else set.lightScheme
        val bar = Geometry.of(scenario)
        val name = "${appearance.palette.name.lowercase()}-${appearance.themeMode.name.lowercase()}"

        // Inicio: papel en el margen, barra en su color y la pestaña de Inicio activa.
        awaitPixel("$name papel", scheme.surface) { it.at(bar.margin, it.height / 2) }
        awaitPixel("$name barra", scheme.surfaceContainerLow) { it.at(bar.margin, bar.barCenterY) }
        awaitPixel("$name inicio activo", scheme.secondaryContainer) {
            it.at(bar.pillX(0), bar.pillY)
        }
        capture("$name-1-home")

        // El segundo lugar de la barra es Vender, que abre una venta: Registros se abre desde Inicio.
        for ((tab, label) in listOf(2 to "products", 3 to "numbers")) {
            tap(bar.tabX(tab), bar.barCenterY)
            awaitPixel("$name $label activo", scheme.secondaryContainer) {
                it.at(bar.pillX(tab), bar.pillY)
            }
            awaitPixel("$name inicio inactivo", scheme.surfaceContainerLow) {
                it.at(bar.pillX(0), bar.pillY)
            }
            capture("$name-${tab + 1}-$label")
        }

        // Vender, en la barra, abre la venta encima, sin barra; atrás vuelve a la pestaña, y atrás otra
        // vez a Inicio. El botón del centro es Decir.
        tap(bar.tabX(1), bar.barCenterY)
        awaitPixel("$name venta sin barra", scheme.surface) { it.at(bar.margin, bar.barCenterY) }
        capture("$name-5-new-sale")
        pressBack()
        awaitPixel("$name vuelve a números", scheme.secondaryContainer) {
            it.at(bar.pillX(3), bar.pillY)
        }
        pressBack()
        awaitPixel("$name vuelve a inicio", scheme.secondaryContainer) {
            it.at(bar.pillX(0), bar.pillY)
        }
    }

    /** Dónde están el margen, la barra, sus pestañas y Vender, en píxeles de la pantalla. */
    private class Geometry(
        private val width: Int,
        private val dp: Float,
        private val barTop: Int,
        private val fontScale: Float,
    ) {
        val margin = (2 * dp).roundToInt()
        val barCenterY = barTop + (BAR_DP / 2 * dp).roundToInt()
        private val tabWidth = (width - CENTER_SLOT_DP * dp) / 4

        // La píldora va arriba del contenido centrado de la pestaña: icono, separación y etiqueta.
        val pillY: Int =
            run {
                val label = LABEL_LINE_SP * fontScale.coerceAtMost(MAX_LABEL_SCALE)
                val content = PILL_HEIGHT_DP + LABEL_GAP_DP + label
                val pillCenter =
                    HAIRLINE_DP + (BAR_DP - HAIRLINE_DP - content) / 2 + PILL_HEIGHT_DP / 2
                barTop + (pillCenter * dp).roundToInt()
            }
        val sellX = width / 2
        val sellY = barTop + ((SELL_HEIGHT_DP / 2 - SELL_OVERHANG_DP) * dp).roundToInt()

        fun tabX(index: Int): Int {
            val before = if (index <
                2
            ) {
                index * tabWidth
            } else {
                2 * tabWidth + CENTER_SLOT_DP * dp + (index - 2) * tabWidth
            }
            return (before + tabWidth / 2).roundToInt()
        }

        // Al lado del icono, dentro de la píldora: el icono no tapa el color que se mira.
        fun pillX(index: Int): Int = tabX(index) + (PILL_SAMPLE_OFFSET_DP * dp).roundToInt()

        companion object {
            fun of(scenario: ActivityScenario<MainActivity>): Geometry {
                var geometry: Geometry? = null
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val insets =
                        requireNotNull(ViewCompat.getRootWindowInsets(decor)) { "Sin inserciones" }
                    val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    val metrics = activity.resources.displayMetrics
                    geometry =
                        Geometry(
                            width = decor.width,
                            dp = metrics.density,
                            barTop = decor.height - bottom - (BAR_DP * metrics.density).toInt(),
                            fontScale = activity.resources.configuration.fontScale,
                        )
                }
                return requireNotNull(geometry) { "La actividad no respondió" }
            }
        }
    }

    private fun awaitAppearance(scenario: ActivityScenario<MainActivity>, expected: Appearance) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        var shown: Appearance? = null
        while (SystemClock.elapsedRealtime() < deadline && shown != expected) {
            scenario.onActivity { shown = it.appearance.value }
            if (shown != expected) SystemClock.sleep(POLL_MILLIS)
        }
        assertEquals(expected, shown)
    }

    // Espera a que el píxel tome el color: las transiciones duran unos cientos de milisegundos.
    private fun awaitPixel(what: String, expected: Color, pixel: (Bitmap) -> Int) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        var last = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val shot = screenshot()
            last = pixel(shot)
            shot.recycle()
            if (close(last, expected.toArgb())) return
            SystemClock.sleep(POLL_MILLIS)
        }
        assertTrue("$what: se esperaba ${hex(expected.toArgb())} y se vio ${hex(last)}", false)
    }

    private fun close(a: Int, b: Int): Boolean = listOf(16, 8, 0).all { shift ->
        abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <=
            TOLERANCE
    }

    private fun hex(argb: Int) = "%06X".format(argb and 0xFFFFFF)

    private fun Bitmap.at(x: Int, y: Int): Int =
        getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))

    private fun screenshot(): Bitmap =
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Sin captura"
        }

    private fun capture(name: String) {
        val dir = captureDir ?: return
        // Deja terminar la transición antes de guardar la imagen que se revisa a ojo.
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = screenshot()
        File(dir, "$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private fun tap(x: Int, y: Int) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                time,
                SystemClock.uptimeMillis(),
                action,
                x.toFloat(),
                y.toFloat(),
                0,
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    private fun pressBack() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val time = SystemClock.uptimeMillis()
            val event = KeyEvent(time, time, action, KeyEvent.KEYCODE_BACK, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        }
    }

    private suspend fun ports(): DataPorts = (app.container.data.open() as DataAccess.Ready).ports

    // Las preferencias se cambian fuera de una transacción: no cuentan como un cambio sin respaldar.
    private suspend fun write(appearance: Appearance) {
        val ports = ports()
        val now = app.container.clock.now()
        ports.settings.updatePreference(PreferenceChange.Palette(appearance.palette), now)
        ports.settings.updatePreference(PreferenceChange.Theme(appearance.themeMode), now)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 100L
        const val SETTLE_MILLIS = 700L
        const val TOLERANCE = 3

        // Medidas de la barra en dp, las del sistema de diseño.
        const val BAR_DP = 72f
        const val HAIRLINE_DP = 1f
        const val CENTER_SLOT_DP = 92f
        const val PILL_HEIGHT_DP = 28f
        const val LABEL_GAP_DP = 4f
        const val LABEL_LINE_SP = 16f
        const val MAX_LABEL_SCALE = 1.15f
        const val SELL_HEIGHT_DP = 60f
        const val SELL_OVERHANG_DP = 15f
        const val PILL_SAMPLE_OFFSET_DP = 19f
    }
}
