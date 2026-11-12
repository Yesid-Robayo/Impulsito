package io.github.yesidrobayo.shopledger.ui.settings

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.usecase.settings.AppLockSettings
import io.github.yesidrobayo.shopledger.ui.backup.BackupWording
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.rescue.SupportLine
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las capturas de Ajustes para la puerta de calidad visual: arriba, con el aviso sin bloqueo de
 * pantalla y un respaldo que pide atención; abajo, con `Acerca de`; y las hojas de los pasos para
 * borrar y de las licencias. Con la paleta predeterminada y otra, en claro y en oscuro. La escala de
 * la letra y el ancho de la ventana son los del teléfono.
 *
 * Solo corre con el argumento `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)

    private val supportLines =
        listOf(
            SupportLine(resources.getString(R.string.support_field_android), "16 (36)"),
            SupportLine(resources.getString(R.string.support_field_device), "Teléfono de muestra"),
            SupportLine(resources.getString(R.string.support_field_last_error), "LOCAL_INVARIANT"),
        )

    private val overview =
        SettingsOverviewUiState(
            backup =
                BackupWording(
                    BackupOverview(
                        report = BackupStatusReport(BackupStatus.FAILED, 4, savingSoon = false),
                        destinationKind = BackupDestinationKind.WEBDAV,
                        lastSuccessAt = now,
                        failureCode = BackupFailureCode.LOCAL_INVARIANT,
                        staleReason = null,
                        meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
                        nextMeteredUploadAt = null,
                        lastFullReadAt = null,
                        computedAt = now,
                        invariantFailures = 3,
                    ),
                    providerLabel = null,
                    zone = null,
                ),
            destination = BackupDestinationInfo.WebDav("cuenta@example.com", "dav.example.com"),
            currency = CurrencyCode.of("EUR"),
        )

    @Test
    fun capturesTheScreenAndItsSheetsInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.FOREST_HONEY)) {
            for (dark in listOf(false, true)) {
                val prefix = "${palette.name.lowercase()}-${if (dark) "dark" else "light"}"
                show(palette, dark)
                shoot(requireNotNull(dir), "$prefix-top")
                compose.onNodeWithText(resources.getString(R.string.settings_theme_title))
                    .performScrollTo()
                shoot(dir, "$prefix-appearance")
                compose.onNodeWithText(resources.getString(R.string.settings_privacy))
                    .performScrollTo()
                shoot(dir, "$prefix-bottom")
                compose.onNodeWithText(resources.getString(R.string.settings_delete_data))
                    .performScrollTo()
                    .performClick()
                shoot(dir, "$prefix-delete-guide")
                show(palette, dark)
                compose.onNodeWithText(resources.getString(R.string.settings_licenses))
                    .performScrollTo()
                    .performClick()
                shoot(dir, "$prefix-licenses")
                show(palette, dark)
                compose.onNodeWithText(resources.getString(R.string.support_help))
                    .performScrollTo()
                    .performClick()
                shoot(dir, "$prefix-support")
            }
        }
    }

    @Test
    fun capturesTheColorsSheetInEveryPaletteAndMode() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        for (palette in ColorPalette.entries) {
            for (dark in listOf(false, true)) {
                show(palette, dark)
                compose.onNodeWithText(resources.getString(R.string.settings_palette_title))
                    .performScrollTo()
                    .performClick()
                shoot(
                    requireNotNull(dir),
                    "colors-${palette.name.lowercase()}-${if (dark) "dark" else "light"}",
                )
            }
        }
    }

    private fun show(palette: ColorPalette, dark: Boolean) {
        // Primero nada: una hoja abierta de la captura anterior se cierra antes de dibujar de nuevo.
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = palette, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) {
                        SettingsEntryScreen(
                            onOpen = {},
                            onBack = {},
                            tipsEnabled = true,
                            profile = BusinessProfile("Tienda de muestra", BasisPoints(3_000L)),
                            security =
                                SecuritySettingsUiState(
                                    AppLockSettings(true, AppLockTimeout.ONE_MINUTE),
                                ),
                            overview = overview,
                            deviceLockMissing = true,
                            version = "1.0.0 (7)",
                            support = supportLines,
                            usage = UsageSettingsUiState(
                                stockAlerts = true,
                                theme = ThemeMode.SYSTEM,
                            ),
                            notificationsBlocked = true,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(dir: File, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}
