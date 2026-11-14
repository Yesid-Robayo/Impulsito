package io.github.yesidrobayo.shopledger.ui.location

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de ubicaciones y transferencias y su formulario en el teléfono: cada fila se lee
 * entera, una sola ubicación explica para qué sirve otra, archivar con stock ofrece transferir, y
 * el formulario avisa el negativo y dice qué falta.
 *
 * Los textos se buscan por recurso, así la prueba vale en el idioma del emulador. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class LocationsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // Una fila se lee entera por TalkBack: su texto solo está en su descripción.
    private fun count(text: String) = compose
        .onAllNodes(
            hasText(text, substring = true) or hasContentDescription(text, substring = true),
        ).fetchSemanticsNodes()
        .size

    @Test
    fun aLocationRowReadsItsNameKindAndProducts() {
        compose.setContent { PreviewTheme { LocationsScreen(LocationSamples.ready(), {}) } }

        val products = resources.getQuantityString(R.plurals.location_products_with_stock, 1, 1)
        val reading = listOf(
            LocationSamples.STALL.details.name.orEmpty(),
            resources.getString(R.string.location_kind_stall),
            products,
        ).joinToString()
        assertEquals(1, count(reading))
        // En algunos idiomas el grupo y la marca de la fila archivada se escriben igual.
        assertTrue(count(resources.getString(R.string.location_archived_group)) >= 1)
        val archived = listOf(
            LocationSamples.WAREHOUSE.details.name.orEmpty(),
            resources.getString(R.string.location_kind_warehouse),
            resources.getString(R.string.status_archived),
        ).joinToString()
        assertEquals(1, count(archived))
        assertEquals(1, count(resources.getString(R.string.location_new_action)))
    }

    @Test
    fun aSingleLocationExplainsWhatASecondOneIsFor() {
        val actions = mutableListOf<LocationsAction>()
        val single = LocationsUiState(content = LocationsContent.Ready(LocationSamples.SINGLE))
        compose.setContent { PreviewTheme { LocationsScreen(single, { actions += it }) } }

        assertEquals(1, count(resources.getString(R.string.location_empty_title)))
        compose.onNodeWithText(resources.getString(R.string.location_tab_transfers)).performClick()

        assertEquals(listOf(LocationsAction.SelectTab(LocationsTab.TRANSFERS)), actions)
    }

    @Test
    fun archivingWithStockOffersANewTransferFromThatLocation() {
        val actions = mutableListOf<LocationsAction>()
        val state = LocationSamples.ready().copy(
            selectedId = LocationSamples.STALL.id,
            detail = LocationDetailContent.Ready(
                LocationSamples.STALL,
                LocationSamples.ITEMS,
                hasStock = true,
            ),
            archiveBlocked = true,
        )
        compose.setContent { PreviewTheme { LocationsScreen(state, { actions += it }) } }

        assertEquals(1, count(resources.getString(R.string.location_error_archive_with_stock)))
        compose.onNodeWithText(resources.getString(R.string.location_new_transfer_action))
            .performClick()

        assertTrue(LocationsAction.NewTransfer(LocationSamples.STALL.id) in actions)
    }

    @Test
    fun aVoidedTransferReadsItsRouteProductsAndStatus() {
        compose.setContent {
            PreviewTheme { LocationsScreen(LocationSamples.ready(LocationsTab.TRANSFERS), {}) }
        }

        val products = resources.getQuantityString(R.plurals.location_transfer_products, 1, 1)
        val reading = resources.getString(
            R.string.a11y_transfer_row,
            resources.getString(R.string.seed_locations_main),
            LocationSamples.STALL.details.name.orEmpty(),
            products,
        )
        val voided = resources.getString(R.string.status_voided)
        assertEquals(1, count(listOf(reading, voided).joinToString()))
        assertEquals(1, count(resources.getString(R.string.location_new_transfer_action)))
    }

    @Test
    fun theFormWarnsTheNegativeOriginAndSaysWhatIsMissing() {
        val state = NewTransferUiState(
            loading = false,
            locations = listOf(LocationSamples.MAIN, LocationSamples.STALL),
            fromId = LocationSamples.MAIN.id,
            toId = LocationSamples.MAIN.id,
            endError = TransferEnd.TO,
            lines = listOf(
                TransferLineUi(
                    1L,
                    7L,
                    "Arroz",
                    LocationSamples.UNIT,
                    "9",
                    leftAtOrigin = QuantityMilli(-1_000L),
                ),
            ),
            showNoLines = true,
        )
        compose.setContent { PreviewTheme { NewTransferScreen(state, {}) } }

        assertEquals(1, count(resources.getString(R.string.location_error_same)))
        assertEquals(1, count(resources.getString(R.string.location_error_no_lines)))
        // El aviso de la línea nombra el origen: la inicial, con su nombre traducido.
        val main = resources.getString(R.string.seed_locations_main)
        assertTrue(count(main) >= 3)
        assertTrue(count(resources.getString(R.string.location_transfer_register_action)) >= 1)
    }
}
