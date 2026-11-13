package io.github.yesidrobayo.shopledger.ui.sale

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas del historial de ventas para la puerta de calidad visual: varios días con una venta
 * anulada, varios pagos y un nombre largo; con filtro de período; vacío, con filtro sin resultados,
 * con error y con la hoja de métodos; y en ventana ancha con el detalle a la derecha. Con la paleta
 * predeterminada y otra, en claro y en oscuro. Solo corre con `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class SaleHistoryGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val zone = BusinessTimeZone.of("Etc/GMT+5")
    private val methods = listOf(
        SaleMethod(1L, "CASH", null, false),
        SaleMethod(2L, "CARD", null, false),
        SaleMethod(3L, null, "Billetera del barrio", false),
    )

    private fun row(
        id: Long,
        daysAgo: Int,
        minutes: Int,
        product: String,
        total: Long,
        lines: Int = 1,
        payments: Int = 1,
        voided: Boolean = false,
    ) = SaleHistoryRow(
        id = id,
        occurredAt = Instant.fromEpochMilliseconds(
            NOW - daysAgo * DAY - minutes * MINUTE,
        ),
        businessDate = DETAIL_DATE.plusDays(-daysAgo),
        total = Money(total),
        voided = voided,
        firstProductName = product,
        lineCount = lines,
        methodName = "Efectivo",
        paymentCount = payments,
    )

    private fun days(): HistoryContent.Days {
        val rows = listOf(
            row(1_210L, 0, 5, "Aceite 900 ml", 25_000L, lines = 3),
            row(1_209L, 0, 40, "Arroz 1 kg", 4_200L),
            row(1_208L, 0, 95, "Huevos x 30", 16_800L, payments = 2),
            row(1_207L, 0, 130, "Queso fresco", 22_000L, voided = true),
            row(
                1_206L,
                1,
                30,
                "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos",
                1_234_567_890_123L,
                lines = 2,
            ),
            row(1_205L, 1, 200, "Azúcar x 2", 3_800L),
            row(1_204L, 2, 10, "Jabón de avena", 31_000L, lines = 3),
        )
        val totals = mapOf(0 to 46_000L, 1 to 1_234_567_893_923L, 2 to 31_000L)
        return HistoryContent.Days(
            rows.groupBy { it.businessDate }.entries.mapIndexed { index, (date, dayRows) ->
                HistoryDay(date, Money(requireNotNull(totals[index])), dayRows)
            },
            endReached = false,
        )
    }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val base = SaleHistoryUiState(
            filters = HistoryFilters(methods = methods, weekStartDay = DayOfWeek.MONDAY),
            content = days(),
            today = DETAIL_DATE,
            zone = zone,
        )
        val scenes = listOf(
            "list" to base,
            "period" to base.copy(
                filters = base.filters.copy(period = ReportPeriod.Day(DETAIL_DATE), methodId = 2L),
                content = HistoryContent.Days(days().days.take(1), endReached = true),
            ),
            "empty" to base.copy(content = HistoryContent.Empty),
            "filtered" to base.copy(
                filters = base.filters.copy(onlyVoided = true),
                content = HistoryContent.EmptyFiltered,
            ),
            "error" to base.copy(content = HistoryContent.ReadError),
            "methods" to base.copy(filterSheet = true),
            "periods" to base.copy(periodSheet = true),
            "wide" to base.copy(selectedSaleId = 1_204L),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state) in scenes) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(bars, bars)
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides false,
                                    ) {
                                        SaleHistoryScreen(
                                            uiState = state,
                                            onAction = {},
                                            detail = { _ ->
                                                SaleDetailScreen(
                                                    SaleDetailUiState.Ready(saleDetailView()),
                                                    {},
                                                )
                                            },
                                        )
                                    }
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

        /** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en `Etc/GMT+5`. */
        const val NOW = 1_793_631_600_000L
        const val DAY = 86_400_000L
        const val MINUTE = 60_000L
    }
}
