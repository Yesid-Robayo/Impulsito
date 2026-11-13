package io.github.yesidrobayo.shopledger.ui.purchase

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetail
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetailLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.VoidStockEffect
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas del historial y del detalle de compras para la puerta de calidad visual: la lista con una
 * compra anulada, vacía, el detalle activo, la hoja de anular con stock negativo y el detalle
 * anulado, en claro y en oscuro. La letra es la del sistema. Solo corre con `captureDir`. Datos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class PurchaseRecordsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val unit = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)

    private fun row(
        id: Long,
        supplier: String?,
        voided: Boolean = false,
        reference: String? = null,
    ) = PurchaseHistoryRow(id, now, today, Money(id * 12_345L), voided, supplier, 3, reference)

    private val history = PurchaseHistoryUiState(
        today = today,
        content = PurchaseHistoryContent.Days(
            listOf(
                PurchaseDay(
                    today,
                    Money(98_760L),
                    listOf(
                        row(8, "Distribuidora inventada", reference = "F-0042"),
                        row(7, null, voided = true),
                    ),
                ),
                PurchaseDay(
                    today.plusDays(-1),
                    Money(74_070L),
                    listOf(
                        row(6, "Mayorista inventado con un nombre largo para ver cómo se corta"),
                    ),
                ),
            ),
            endReached = true,
        ),
    )

    private fun purchase(voided: Boolean) = Purchase(
        id = 318,
        content = NewPurchase(
            supplierId = 1,
            invoiceReference = "F-0042",
            netTotal = Money(64_008),
            taxTotal = Money.ZERO,
            total = Money(64_008),
            costTotal = Money(64_008),
            amountPaid = Money(64_008),
            moneyAccountId = null,
            dueDate = null,
            locationId = 1,
            foreignCurrency = null,
            occurredAt = now,
            businessDate = today,
            createdAt = now,
        ),
        voiding = if (voided) Voiding(now, VoidReason.ENTRY_ERROR, null) else null,
    )

    private fun line(id: Long, name: String, milli: Long, cost: Long, total: Long) =
        PurchaseDetailLine(
            line = PurchaseLine(
                id,
                318,
                NewPurchaseLine(
                    productId = id,
                    unitId = 1,
                    unitFactor = QuantityMilli.ofUnits(1),
                    enteredQuantity = QuantityMilli(milli),
                    quantity = QuantityMilli(milli),
                    unitCost = Money(cost),
                    lineTotal = Money(total),
                    taxRate = BasisPoints.ZERO,
                    taxNameSnapshot = null,
                    taxAmount = Money.ZERO,
                    costAmount = Money(total),
                    lotId = null,
                    referenceUnitCost = null,
                ),
            ),
            productName = name,
            productArchived = id == 2L,
            unit = unit,
        )

    private fun detail(voided: Boolean) = PurchaseDetail(
        purchase = purchase(voided),
        supplierName = "Distribuidora inventada",
        lines = listOf(
            line(1, "Jabón de avena", 24_000, 417, 10_008),
            line(2, "Aceite 900 ml", 12_000, 4_500, 54_000),
        ),
        voidEffects = listOf(
            VoidStockEffect(
                1,
                "Jabón de avena",
                unit,
                QuantityMilli(24_000),
                QuantityMilli(-3_000),
            ),
            VoidStockEffect(2, "Aceite 900 ml", unit, QuantityMilli(12_000), QuantityMilli(4_000)),
        ),
        voidedOn = if (voided) today else null,
    )

    @Test
    fun drawsThePurchaseRecordsInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
            "history" to { PurchaseHistoryScreen(history, onAction = {}) },
            "history-filtered" to {
                PurchaseHistoryScreen(
                    history.copy(
                        filters = PurchaseFilters(
                            period = ReportPeriod.Week(today),
                            supplier = SupplierFilter.Of(1L),
                            suppliers = listOf(SupplierChoice(1L, "Distribuidora inventada")),
                        ),
                    ),
                    onAction = {},
                )
            },
            "history-filter-sheet" to {
                PurchaseHistoryScreen(
                    history.copy(
                        filters = PurchaseFilters(
                            supplier = SupplierFilter.None,
                            onlyVoided = true,
                            suppliers = listOf(SupplierChoice(1L, "Distribuidora inventada")),
                        ),
                        filterSheet = true,
                    ),
                    onAction = {},
                )
            },
            "history-empty" to {
                PurchaseHistoryScreen(
                    PurchaseHistoryUiState(content = PurchaseHistoryContent.Empty),
                    onAction = {},
                )
            },
            "detail" to {
                PurchaseDetailScreen(PurchaseDetailUiState.Ready(detail(false)), onAction = {})
            },
            "void-sheet" to {
                PurchaseDetailScreen(
                    PurchaseDetailUiState.Ready(detail(false), sheet = PurchaseVoidSheet()),
                    onAction = {},
                )
            },
            "detail-voided" to {
                PurchaseDetailScreen(PurchaseDetailUiState.Ready(detail(true)), onAction = {})
            },
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, content) in scenes) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        activity.setContent {
                            AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                    LocalDiscreetMode provides false,
                                ) {
                                    key(name, dark) { content() }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "$mode-purchases-$name.png"))
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
