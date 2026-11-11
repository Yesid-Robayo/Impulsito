package io.github.yesidrobayo.shopledger.ui.backup

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.home.HomeScreen
import io.github.yesidrobayo.shopledger.ui.home.previewHomeContent
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas del respaldo para la puerta de calidad visual: la pantalla al día con WebDAV y su lista,
 * con la nube del teléfono y la revisión vencida, con un fallo y la lista con error, y sin destino;
 * e Inicio con su chip y su banner. Con la paleta predeterminada y otra, en claro y en oscuro. Solo
 * corre con `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class BackupGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsTheBackupScreenAndHomeInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val slots = BackupDestinationKind.DOCUMENT_SLOTS
        val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
            "upToDate" to {
                BackupScreen(previewBackupState(previewOverview(BackupStatus.UP_TO_DATE)), {})
            },
            "handedOff" to {
                BackupScreen(
                    previewBackupState(
                        previewOverview(
                            BackupStatus.HANDED_OFF,
                            slots,
                            notices = setOf(BackupNotice.HUMAN_CHECK_DUE),
                        ),
                    ),
                    {},
                )
            },
            "failed" to {
                BackupScreen(
                    previewBackupState(
                        previewOverview(
                            BackupStatus.FAILED,
                            failure = BackupFailureCode.QUOTA_EXCEEDED,
                        ),
                        copies = BackupCopyList.Failed,
                    ),
                    {},
                )
            },
            "notConnected" to {
                BackupScreen(
                    previewBackupState(previewOverview(BackupStatus.NOT_CONNECTED, kind = null)),
                    {},
                )
            },
            "homeStale" to {
                HomeScreen(
                    previewHomeContent(),
                    {},
                    backup = previewBackupState(
                        previewOverview(
                            BackupStatus.STALE,
                            pending = 3,
                            notices = setOf(BackupNotice.LOW_DEVICE_STORAGE),
                        ),
                    ).wording,
                )
            },
            "homeHandedOff" to {
                HomeScreen(
                    previewHomeContent(),
                    {},
                    backup = previewBackupState(
                        previewOverview(BackupStatus.HANDED_OFF, slots),
                    ).wording,
                )
            },
            "homeUploading" to {
                HomeScreen(
                    previewHomeContent(),
                    {},
                    backup = previewBackupState(previewOverview(BackupStatus.UPLOADING)).wording,
                )
            },
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, scene) in scenes) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(bars, bars)
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides false,
                                    ) { scene() }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        save(
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

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
