package io.github.yesidrobayo.shopledger.ui.sale

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarContent
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de los mensajes de deshacer para la puerta de calidad visual: venta registrada con
 * `Deshacer`, venta anulada, y la falla con `Reintentar`, con la paleta predeterminada y otra, en
 * claro y en oscuro. La letra y la ventana son las del sistema. Solo corre con `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class SaleUndoGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsTheUndoMessagesInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        activity.setContent {
                            AppTheme(palette = palette, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                ) {
                                    Messages()
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "${palette.name.lowercase()}-$mode-undo.png"))
                }
            }
        }
    }

    @Composable
    private fun Messages() {
        val formats = LocalAppFormats.current
        val amount = rememberMoneyDisplay(Money(1_234_500), formats.money).text.text
        val texts = SaleUndoTexts(
            undone = stringResource(R.string.confirm_sale_voided),
            failed = stringResource(R.string.error_sale_void),
            retry = stringResource(R.string.action_retry),
        )
        val messages = listOf(
            SaleUndo.registeredMessage(
                stringResource(R.string.confirm_sale_registered, amount),
                stringResource(R.string.action_undo),
            ),
            SaleUndo.outcomeMessage(SaleUndoOutcome.UNDONE, texts),
            SaleUndo.outcomeMessage(SaleUndoOutcome.FAILED, texts),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            messages.forEach { message: AppSnackbarVisuals ->
                AppSnackbarContent(
                    message = message.message,
                    variant = message.variant,
                    actionLabel = message.actionLabel,
                )
            }
        }
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
