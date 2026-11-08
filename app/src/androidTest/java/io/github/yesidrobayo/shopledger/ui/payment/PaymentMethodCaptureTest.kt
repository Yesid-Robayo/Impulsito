package io.github.yesidrobayo.shopledger.ui.payment

import android.graphics.Bitmap
import android.graphics.Color.BLACK
import android.graphics.Color.TRANSPARENT
import android.graphics.Color.WHITE
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodOptions
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la lista y el formulario de métodos de pago para la puerta de calidad visual: cada
 * estado con la paleta predeterminada y otra, en claro y en oscuro. La escala de la letra y el ancho
 * de la ventana son los del sistema: se corre una vez por combinación.
 *
 * Solo corre con el argumento `captureDir`, que nombra la carpeta dentro de la caché de la app.
 */
@RunWith(AndroidJUnit4::class)
class PaymentMethodCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val states: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "list" to {
                PaymentMethodsScreen(
                    PaymentMethodsUiState.Ready(
                        SAMPLE_ACTIVE,
                        SAMPLE_ARCHIVED,
                        archivedExpanded = true,
                    ),
                    {},
                )
            },
            "empty" to {
                PaymentMethodsScreen(PaymentMethodsUiState.Ready(emptyList(), SAMPLE_ARCHIVED), {})
            },
            "sorting" to {
                PaymentMethodsScreen(
                    PaymentMethodsUiState.Ready(
                        SAMPLE_ACTIVE,
                        emptyList(),
                        sorting = SortingState(SAMPLE_ACTIVE),
                    ),
                    {},
                )
            },
            "error" to { PaymentMethodsScreen(PaymentMethodsUiState.ReadError, {}) },
            "form" to {
                PaymentMethodFormScreen(
                    PaymentMethodFormUiState.Editing(
                        methodId = SAMPLE_ACTIVE[1].id,
                        systemKey = SAMPLE_ACTIVE[1].systemKey,
                        form =
                            PaymentMethodForm(
                                kind = PaymentMethodKind.CARD,
                                feeRate = "2,5",
                                feeFixed = "100",
                                settlementDays = "2",
                            ),
                        options = PaymentMethodOptions(SAMPLE_ACTIVE[1], false, activeCount = 6),
                    ),
                    {},
                )
            },
            "form-errors" to {
                PaymentMethodFormScreen(
                    PaymentMethodFormUiState.Editing(
                        methodId = null,
                        systemKey = null,
                        form =
                            PaymentMethodForm(
                                name = "",
                                nameEdited = true,
                                kind = PaymentMethodKind.WALLET,
                                feeRate = "101",
                                priceAdjustment = "-150",
                            ),
                        options = null,
                        errors =
                            mapOf(
                                FormField.NAME to FieldProblem.NAME_BLANK,
                                FormField.FEE_RATE to FieldProblem.FEE_RATE_RANGE,
                                FormField.PRICE_ADJUSTMENT to FieldProblem.PRICE_ADJUSTMENT_RANGE,
                            ),
                        notice = FormNotice.SaveFailed(SaveProblem.OUT_OF_SPACE),
                    ),
                    {},
                )
            },
            "panes" to {
                ListDetailPanes(
                    list = {
                        PaymentMethodsScreen(
                            PaymentMethodsUiState.Ready(SAMPLE_ACTIVE, SAMPLE_ARCHIVED),
                            {},
                            it,
                        )
                    },
                    detail = {
                        PaymentMethodFormScreen(
                            PaymentMethodFormUiState.Editing(
                                methodId = SAMPLE_ACTIVE[0].id,
                                systemKey = SAMPLE_ACTIVE[0].systemKey,
                                form = PaymentMethodForm(kind = PaymentMethodKind.CASH),
                                options = PaymentMethodOptions(SAMPLE_ACTIVE[0], false, 6),
                                cashRounding = Money(50),
                            ),
                            {},
                        )
                    },
                )
            },
            "form-image" to {
                PaymentMethodFormScreen(
                    PaymentMethodFormUiState.Editing(
                        methodId = SAMPLE_ACTIVE[3].id,
                        systemKey = null,
                        form =
                            PaymentMethodForm(
                                name = SAMPLE_ACTIVE[3].details.name.orEmpty(),
                                nameEdited = true,
                                kind = PaymentMethodKind.WALLET,
                                instructions = PreviewSamples.LONG_NAME,
                                image = FormImage.Saved(sampleQr),
                            ),
                        options = PaymentMethodOptions(SAMPLE_ACTIVE[3], false, activeCount = 6),
                    ),
                    {},
                )
            },
            "list-image" to {
                PaymentMethodsScreen(
                    PaymentMethodsUiState.Ready(
                        SAMPLE_ACTIVE,
                        emptyList(),
                        images = mapOf(SAMPLE_ACTIVE[3].id to sampleQr),
                    ),
                    {},
                )
            },
            "form-locked" to {
                PaymentMethodFormScreen(
                    PaymentMethodFormUiState.Editing(
                        methodId = SAMPLE_ACTIVE[0].id,
                        systemKey = SAMPLE_ACTIVE[0].systemKey,
                        form = PaymentMethodForm(kind = PaymentMethodKind.CASH),
                        options = PaymentMethodOptions(SAMPLE_ACTIVE[0], true, activeCount = 1),
                    ),
                    {},
                )
            },
        )

    /**
     * Un código de cobro inventado: un tablero de cuadros, guardado en WebP en la carpeta de imágenes
     * de la app como lo guardaría el importador.
     */
    private val sampleQr: StoredImage by lazy {
        val side = 320
        val cell = 20
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val dark = ((x / cell) + (y / cell) * 3) % 2 == 0
                bitmap.setPixel(x, y, if (dark) BLACK else WHITE)
            }
        }
        val bytes = ByteArrayOutputStream().also {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it)
        }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val image = StoredImage(sha, "image/webp", side, side, bytes.size.toLong())
        val folder = File(instrumentation.targetContext.filesDir, StoredImage.FOLDER).apply {
            mkdirs()
        }
        File(folder, image.fileName).writeBytes(bytes)
        image
    }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, content) in states) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(
                                statusBarStyle = bars,
                                navigationBarStyle = bars,
                            )
                            activity.setContent { Frame(palette, dark, content) }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        save(
                            scenario,
                            File(
                                requireNotNull(dir),
                                "${palette.name.lowercase()}-$mode-$name.png",
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Guarda la pantalla del teléfono. Con un tamaño de pantalla cambiado, como el de tableta, API
     * 26 no da captura del sistema: entonces se dibuja la ventana de la actividad, sin barras.
     */
    private fun save(scenario: ActivityScenario<ComponentActivity>, file: File) {
        var window: Bitmap? = null
        val bitmap =
            instrumentation.uiAutomation.takeScreenshot()
                ?: run {
                    scenario.onActivity { window = it.window.decorView.drawToBitmap() }
                    requireNotNull(window) { "Sin captura" }
                }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}

@Composable
private fun Frame(palette: ColorPalette, dark: Boolean, content: @Composable () -> Unit) {
    AppTheme(palette = palette, darkTheme = dark) {
        CompositionLocalProvider(
            LocalAppFormats provides previewFormats(),
            LocalDiscreetMode provides false,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .systemBarsPadding(),
            ) { content() }
        }
    }
}
