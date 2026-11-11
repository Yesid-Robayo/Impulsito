package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los avisos de nombre y de código repetidos en el formulario de un producto, dibujados en el
 * teléfono: cada uno nombra el producto con que choca, y el del código no habla del nombre. Los
 * nombres son inventados.
 */
@RunWith(AndroidJUnit4::class)
class ProductEditMessagesDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun state(errors: Map<ProductEditField, FieldProblem>) = ProductEditUiState(
        isNew = true,
        // Con un código escrito, `Más detalles` empieza abierto y el campo se ve.
        form = ProductEditForm(name = "Arroz 1 kg", unitId = COUNT.id, barcode = "7701234567890"),
        units = listOf(COUNT),
        errors = errors,
    )

    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun aRepeatedNameShowsTheProductThatHasIt() {
        compose.setContent {
            PreviewTheme {
                ProductEditScreen(
                    state(mapOf(ProductEditField.NAME to FieldProblem.Duplicate("Jabón Líquido"))),
                    onAction = {},
                )
            }
        }

        assertEquals(
            1,
            count(resources.getString(R.string.error_name_duplicate, "Jabón Líquido")),
        )
    }

    @Test
    fun aRepeatedBarcodeShowsItsProductAndNotTheNameWarning() {
        compose.setContent {
            PreviewTheme {
                ProductEditScreen(
                    state(mapOf(ProductEditField.BARCODE to FieldProblem.Duplicate("Café molido"))),
                    onAction = {},
                )
            }
        }

        assertEquals(
            1,
            count(resources.getString(R.string.error_barcode_duplicate, "Café molido")),
        )
        compose
            .onAllNodes(
                hasText(
                    resources.getString(R.string.error_name_duplicate, "Café molido"),
                    substring = true,
                ),
            ).assertCountEquals(0)
    }

    private companion object {
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    }
}
