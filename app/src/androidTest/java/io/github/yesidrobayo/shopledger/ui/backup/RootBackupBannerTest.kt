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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.product.ProductListScreen
import io.github.yesidrobayo.shopledger.ui.product.ProductListUiState
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryScreen
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryUiState
import io.github.yesidrobayo.shopledger.ui.sale.SaleHistoryScreen
import io.github.yesidrobayo.shopledger.ui.sale.SaleHistoryUiState
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsScreen
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsUiState
import io.github.yesidrobayo.shopledger.ui.stock.InventoryScreen
import io.github.yesidrobayo.shopledger.ui.stock.InventoryUiState
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: el banner del respaldo bajo la barra de cada raíz que no es Inicio. Con un estado
 * que pide atención aparece con su acción, y con `Todo guardado`, que solo existe con WebDAV,
 * desaparece. Sin destino no aparece: ese aviso solo va en Inicio.
 *
 * Con el argumento `captureDir` guarda además la galería de las cinco raíces con el respaldo fallido,
 * en claro y en oscuro, con la paleta predeterminada y otra.
 */
@RunWith(AndroidJUnit4::class)
class RootBackupBannerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)

    private val roots: List<Pair<String, @Composable (@Composable () -> Unit) -> Unit>> =
        listOf(
            "sales" to
                { banner -> SaleHistoryScreen(SaleHistoryUiState(), {}, backupBanner = banner) },
            "products" to
                { banner -> ProductListScreen(ProductListUiState(), {}, backupBanner = banner) },
            "inventory" to
                { banner -> InventoryScreen(InventoryUiState(), {}, backupBanner = banner) },
            "numbers" to { banner ->
                StatisticsScreen(StatisticsUiState.Loading, {}, backupBanner = banner)
            },
            "purchases" to { banner ->
                PurchaseHistoryScreen(PurchaseHistoryUiState(), {}, backupBanner = banner)
            },
        )

    @Test
    fun salesHistoryShowsTheBannerOnlyWhenTheBackupNeedsAttention() = check(0)

    @Test
    fun productListShowsTheBannerOnlyWhenTheBackupNeedsAttention() = check(1)

    @Test
    fun inventoryShowsTheBannerOnlyWhenTheBackupNeedsAttention() = check(2)

    @Test
    fun statisticsShowTheBannerOnlyWhenTheBackupNeedsAttention() = check(3)

    @Test
    fun purchaseHistoryShowsTheBannerOnlyWhenTheBackupNeedsAttention() = check(4)

    @Test
    fun capturesTheRootsWithAFailedBackupInBothModes() {
        val dir =
            InstrumentationRegistry.getArguments().getString("captureDir")?.let {
                File(instrumentation.targetContext.cacheDir, it).apply { mkdirs() }
            }
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.FOREST_HONEY)) {
            for (dark in listOf(false, true)) {
                roots.forEach { (name, root) ->
                    show(palette, dark) {
                        root {
                            BackupAttentionBanner(wording(BackupStatus.FAILED), {
                            }, Modifier.rootBannerSpacing())
                        }
                    }
                    shoot(requireNotNull(dir), "${palette.name.lowercase()}-${mode(dark)}-$name")
                }
            }
        }
    }

    private fun check(index: Int) {
        val root = roots[index].second
        var backup by mutableStateOf(wording(BackupStatus.FAILED))
        val actions = mutableListOf<BackupBannerAction>()
        compose.setContent {
            PreviewTheme { root { BackupAttentionBanner(backup, { actions += it }) } }
        }
        val review = resources.getString(R.string.backup_action_review)
        compose.onNodeWithText(review).performClick()
        assertEquals(listOf(BackupBannerAction.OPEN_BACKUP), actions)

        backup = wording(BackupStatus.UP_TO_DATE)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(review).fetchSemanticsNodes().size)

        // Sin destino, el aviso solo va en Inicio: las demás raíces no lo muestran.
        backup = wording(BackupStatus.NOT_CONNECTED, kind = null)
        compose.waitForIdle()
        val choose = resources.getString(R.string.backup_action_choose_destination)
        assertEquals(0, compose.onAllNodesWithText(choose).fetchSemanticsNodes().size)
        val detail = resources.getString(R.string.backup_state_not_connected_detail)
        assertEquals(0, compose.onAllNodesWithText(detail).fetchSemanticsNodes().size)

        // Las esperas normales tampoco lo muestran.
        backup = wording(BackupStatus.PENDING)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(review).fetchSemanticsNodes().size)
    }

    private fun wording(
        status: BackupStatus,
        kind: BackupDestinationKind? = BackupDestinationKind.WEBDAV,
    ) = BackupWording(
        BackupOverview(
            report = BackupStatusReport(status, pendingChanges = 3, savingSoon = false),
            destinationKind = kind,
            lastSuccessAt = now,
            failureCode = null,
            staleReason = null,
            meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
            nextMeteredUploadAt = null,
            lastFullReadAt = null,
            computedAt = now,
        ),
        providerLabel = null,
        zone = null,
    )

    private fun mode(dark: Boolean) = if (dark) "dark" else "light"

    private fun show(palette: ColorPalette, dark: Boolean, content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.waitForIdle()
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = palette, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
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
