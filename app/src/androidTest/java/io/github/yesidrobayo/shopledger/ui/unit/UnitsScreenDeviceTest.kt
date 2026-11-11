package io.github.yesidrobayo.shopledger.ui.unit

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
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
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de unidades y conversiones en el teléfono: los grupos por tipo, el menú según el uso,
 * la lista y el vacío de conversiones, y el resultado en vivo de la hoja con el costo exacto. Con el
 * argumento `captureDir`, además, deja capturas de cada estado en claro y oscuro con dos paletas.
 *
 * Los textos se buscan por recurso, así la prueba vale en el idioma del emulador. Los datos son
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class UnitsScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources

    // Una fila se lee entera por TalkBack: su texto solo está en su descripción.
    private fun count(text: String) = compose
        .onAllNodes(
            hasText(text, substring = true) or hasContentDescription(text, substring = true),
        ).fetchSemanticsNodes()
        .size

    @Test
    fun unitsAreGroupedByKindAndSayIfTheyAllowDecimals() {
        compose.setContent { PreviewTheme { UnitsScreen(READY, onAction = {}) } }

        assertEquals(1, count(resources.getString(R.string.unit_kind_count)))
        assertEquals(1, count(resources.getString(R.string.unit_kind_mass)))
        assertEquals(1, count(resources.getString(R.string.unit_group_archived)))
        assertEquals(1, count(resources.getString(R.string.unit_allows_fraction)))
        // Unidad, caja y el fardo archivado son de conteo o de otro tipo, sin decimales.
        assertEquals(3, count(resources.getString(R.string.unit_whole_only)))
        assertEquals(1, count(resources.getString(R.string.unit_new_action)))
    }

    @Test
    fun theMenuOffersDeleteOnlyForAUnitWithoutUse() {
        compose.setContent { PreviewTheme { UnitsScreen(READY, onAction = {}) } }
        val more = resources.getString(R.string.a11y_more_options)
        val delete = resources.getString(R.string.action_delete)

        // Las filas vigentes, en orden: unidad, caja (sin uso) y kilogramo (en uso).
        compose.onAllNodes(hasContentDescription(more))[1].performClick()
        compose.onNodeWithText(delete).assertExists()
        compose.onNodeWithText(delete).performClick()

        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription(more))[2].performClick()
        compose.onAllNodes(hasText(delete)).assertCountEquals(0)
    }

    @Test
    fun theConversionsTabShowsEachConversionAsAPhraseOrTheEmptyState() {
        val state = mutableStateOf(READY.copy(tab = UnitsTab.CONVERSIONS))
        val actions = mutableListOf<UnitsAction>()
        compose.setContent {
            PreviewTheme { UnitsScreen(state.value, onAction = { actions += it }) }
        }

        assertEquals(1, count("Arroz 1 kg"))
        // TalkBack lee la conversión sin artículo: el producto, la cantidad de la base y la unidad.
        val box = resources.getString(R.string.seed_units_box).lowercase()
        val reading = resources.getString(R.string.a11y_unit_conversion, "Arroz 1 kg", "24", box)
        assertEquals(1, count(reading.substringBefore("24") + "24"))
        assertEquals(1, count(reading.substringAfter("24")))
        assertEquals(1, count(resources.getString(R.string.unit_for_purchase)))
        compose.onNodeWithContentDescription("Arroz 1 kg", substring = true).performClick()
        assertEquals(UnitsAction.EditConversion(SAVED.id), actions.last())

        state.value = READY.copy(tab = UnitsTab.CONVERSIONS, conversions = emptyList())
        compose.waitForIdle()
        assertEquals(1, count(resources.getString(R.string.unit_empty_title)))
        assertEquals(1, count(resources.getString(R.string.unit_empty_body)))
    }

    @Test
    fun theSheetReadsTheResultLiveWithTheCostPerBaseUnit() {
        compose.setContent {
            PreviewTheme {
                UnitsScreen(READY, onAction = {
                }, conversionEditor = EDITING, onConversionEditorAction = {})
            }
        }

        // 24 unidades valen 91.200: 3.800 por unidad, el mismo costo que muestra el formulario del
        // producto en sus capturas. El caso de la caja de 24 por 10000, con 417, lo prueba el dominio.
        assertEquals(1, count("3.800"))
    }

    @Test
    fun aCostBelowTheMinorUnitIsShownWithItsDecimalsAndHiddenInDiscreetMode() {
        val discreet = mutableStateOf(false)
        val cheap = RICE.copy(
            onHand = QuantityMilli(1_000_000L),
            stockValue = Money(400L),
            averageCost = Money.ZERO,
        )
        val state = READY.copy(products = listOf(cheap))
        compose.setContent {
            PreviewTheme(discreet = discreet.value) {
                UnitsScreen(state, onAction = {
                }, conversionEditor = EDITING, onConversionEditorAction = {})
            }
        }

        // La caja de 1000 por 400 cuesta 0,4 por unidad, y no aparece como sin costo.
        assertEquals(1, count("0,4"))

        discreet.value = true
        compose.waitForIdle()
        assertEquals(0, count("0,4"))
        // El factor sigue visible, en el campo y en la frase.
        assertTrue(count("24") >= 2)
    }

    internal companion object {
        const val SETTLE_MILLIS = 900L
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
        val KILO = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
        val BOX = MeasureUnit(10L, "BOX", null, null, UnitKind.COUNT, false, null)
        val OLD =
            MeasureUnit(
                20L,
                null,
                "Fardo",
                null,
                UnitKind.OTHER,
                false,
                Instant.fromEpochMilliseconds(5L),
            )
        val RICE =
            Product(
                id = 7L,
                details =
                    ProductDetails(
                        "Arroz 1 kg", "arroz 1 kg", null, null, COUNT.id, ProductStockMode.TRACKED,
                        false, Money.ZERO, QuantityMilli.ZERO, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                    ),
                salePrice = Money(5_000L),
                initialCost = Money.ZERO,
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
        val SAVED =
            ProductConversion(
                30L,
                RICE.id,
                ConversionDetails(BOX.id, 24_000L, null, null, forSale = false, forPurchase = true),
            )
        val READY =
            UnitsUiState(
                loading = false,
                active = listOf(COUNT, KILO, BOX),
                archived = listOf(OLD),
                inUse = setOf(COUNT.id, KILO.id),
                conversions = listOf(ConversionEntry(SAVED, RICE)),
                products = listOf(RICE),
            )
        val EDITING =
            ConversionEditor(
                editingId = SAVED.id,
                productId = RICE.id,
                productLocked = true,
                unitId = BOX.id,
                factor = "24",
                originalFactorMilli = 24_000L,
                forSale = false,
            )
    }
}
