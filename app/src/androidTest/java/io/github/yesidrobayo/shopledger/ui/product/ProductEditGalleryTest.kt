package io.github.yesidrobayo.shopledger.ui.product

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas del formulario de un producto para la puerta de calidad visual: cada estado con la
 * paleta predeterminada y otra, en claro y en oscuro. La escala de la letra y el tamaño de la
 * ventana son los del sistema.
 *
 * Solo corre con el argumento `captureDir`. Los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class ProductEditGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state) in states()) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(
                                statusBarStyle = bars,
                                navigationBarStyle = bars,
                            )
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides (name == LOCKED),
                                    ) {
                                        key(name) {
                                            ProductEditScreen(uiState = state, onAction = {})
                                        }
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

    private fun states(): List<Pair<String, ProductEditUiState>> {
        val filled = ProductEditUiState(
            isNew = true,
            form = ProductEditForm(
                name = "Arroz 1 kg",
                categoryId = GRAINS.id,
                unitId = COUNT.id,
                salePrice = "5000",
                cost = "3800",
                initialStock = "24",
                minStock = "5",
            ),
            categories = listOf(GRAINS),
            units = listOf(COUNT),
            profit = ProfitPreview.Profit(Money(1_200L), Margin.Defined(BasisPoints(2_400L))),
            dirty = true,
        )
        return listOf(
            "new" to
                ProductEditUiState(
                    isNew = true,
                    form = ProductEditForm(unitId = COUNT.id),
                    units = listOf(COUNT),
                ),
            "filled" to filled,
            "loss" to filled.copy(
                form = filled.form.copy(
                    name = LONG_NAME,
                    cost = "5200",
                ),
                profit = ProfitPreview.Loss(Money(200L)),
                errors = mapOf(ProductEditField.NAME to FieldProblem.Duplicate("Café")),
                notice = ProductEditNotice.SaveFailed(outOfSpace = false),
            ),
            "nocost" to filled.copy(
                form = filled.form.copy(cost = ""),
                profit = ProfitPreview.NoCost,
                notice = ProductEditNotice.CurrencyUnconfirmed,
            ),
            LOCKED to filled.copy(
                isNew = false,
                unitLocked = true,
                costLocked = true,
                averageCost = Money(3_800L),
            ),
            "discard" to filled.copy(confirmingDiscard = true),
            // Un servicio: sin stock que contar, con el modo de stock a la vista.
            "service" to filled.copy(
                form = filled.form.copy(
                    name = "Corte de cabello",
                    stockMode = ProductStockMode.UNTRACKED,
                    initialStock = "",
                    minStock = "",
                ),
            ),
            // Un producto con historia y sus unidades de compra y venta.
            "product-conversions" to filled.copy(
                isNew = false,
                units = listOf(COUNT, BOX, PACK),
                unitLocked = true,
                stockModeLocked = true,
                product = RICE,
                conversions = listOf(
                    ProductConversion(
                        1L,
                        RICE.id,
                        ConversionDetails(BOX.id, 24_000L, null, null, false, true),
                    ),
                    ProductConversion(
                        2L,
                        RICE.id,
                        ConversionDetails(PACK.id, 6_000L, null, null, true, true),
                    ),
                ),
            ),
        )
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        const val LOCKED = "locked"

        // El nombre más largo que admite un producto: 80 caracteres.
        const val LONG_NAME =
            "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos"
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
        val BOX = MeasureUnit(10L, "BOX", null, null, UnitKind.COUNT, false, null)
        val PACK = MeasureUnit(11L, "PACK", null, null, UnitKind.COUNT, false, null)
        val RICE =
            Product(
                id = 7L,
                details =
                    ProductDetails(
                        "Arroz 1 kg", "arroz 1 kg", 1L, null, COUNT.id, ProductStockMode.TRACKED,
                        false, Money.ZERO,
                        QuantityMilli(
                            5_000L,
                        ),
                        null, null, null, null, null, null,
                        null, null, null, null, null, null,
                    ),
                salePrice = Money(5_000L),
                initialCost = Money(3_800L),
                averageCost = Money(3_800L),
                stockValue = Money(91_200L),
                onHand = QuantityMilli(24_000L),
                replacementCost = Money.ZERO,
                replacementCostAt = null,
                priceReviewSince = null,
                archivedAt = null,
                createdAt = Instant.fromEpochMilliseconds(0L),
                updatedAt = Instant.fromEpochMilliseconds(0L),
            )
        val GRAINS = Category(
            id = 1L,
            details = CategoryDetails("Granos", "granos", null),
            createdAt = Instant.fromEpochMilliseconds(0L),
            updatedAt = Instant.fromEpochMilliseconds(0L),
        )
    }
}
