package io.github.yesidrobayo.shopledger.ui.production

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import kotlinx.datetime.DayOfWeek
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de producción, con datos inventados: el ejemplo de las velas, un nombre de 80
// caracteres, un monto de trece cifras, un insumo que no alcanza, uno sin stock y las variantes
// vacías.

private val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
private val KILOGRAM = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
private val TODAY = requireNotNull(BusinessDate.parse("2026-11-14"))
private val YESTERDAY = requireNotNull(BusinessDate.parse("2026-11-13"))

internal fun previewProductionForm(): ProductionFormUiState {
    val s = PreviewSamples
    return ProductionFormUiState(
        loading = false,
        product = ProductionProductUi(1L, s.LONG_NAME, UNIT),
        quantityText = "40",
        inputs = listOf(
            ProductionInputUi(
                2L,
                s.SHORT_NAME,
                KILOGRAM,
                "2",
                true,
                QuantityMilli(3_200),
                null,
                null,
            ),
            ProductionInputUi(
                3L,
                s.SECOND_NAME,
                UNIT,
                "12",
                true,
                QuantityMilli(8_000),
                QuantityMilli(-4_000),
                null,
            ),
            ProductionInputUi(4L, s.THIRD_NAME, UNIT, "40", false, QuantityMilli.ZERO, null, null),
        ),
        figures = ProductionFiguresUi(
            totalCost = s.LONG_AMOUNT,
            unitCost = Money(533L),
            averageBefore = Money(1_150L),
            averageAfter = Money(1_180L),
            hasUncosted = true,
        ),
        noteText = s.SHORT_NAME,
    )
}

/**
 * El formulario con lotes: el primer insumo ofrece sus lotes, con el segundo elegido, y lo
 * producido pide el código y el vencimiento de un lote nuevo.
 */
internal fun previewProductionFormWithLots(): ProductionFormUiState {
    val form = previewProductionForm()
    val longCode = PreviewSamples.LONG_NAME.take(60)
    val choices = LotChoices(
        lots = listOf(
            LotChoice(LotStock(11L, "A-118", YESTERDAY, false, QuantityMilli(1_200)), null),
            LotChoice(LotStock(12L, longCode, TODAY, false, QuantityMilli(2_000)), null),
        ),
        unassigned = null,
    )
    return form.copy(
        inputs = listOf(form.inputs.first().copy(lots = choices, chosenLotId = 12L)) +
            form.inputs.drop(1),
        outputLot = ProductionLotUi(
            code = "P-40",
            expiry = TODAY,
            existing = false,
            existingExpiry = null,
            tooLong = false,
        ),
        today = TODAY,
        weekStartDay = DayOfWeek.MONDAY,
    )
}

internal fun previewProductions(): ProductionsUiState {
    val s = PreviewSamples
    return ProductionsUiState(
        loading = false,
        hasRecipes = true,
        days = listOf(
            ProductionDayUi(
                TODAY,
                listOf(
                    item(1L, s.SHORT_NAME, UNIT, 10_000, Money(17_500L), false),
                    item(2L, s.LONG_NAME, KILOGRAM, 2_500, s.LONG_AMOUNT, true),
                ),
            ),
            ProductionDayUi(
                YESTERDAY,
                listOf(
                    item(3L, s.THIRD_NAME, UNIT, 40_000, Money(21_300L), false),
                ),
            ),
        ),
    )
}

private fun item(
    id: Long,
    name: String,
    unit: MeasureUnit,
    milli: Long,
    cost: Money,
    voided: Boolean,
) = ProductionItemUi(id, name, unit, QuantityMilli(milli), cost, voided)

internal fun previewProductionDetail(): ProductionsUiState {
    val s = PreviewSamples
    return previewProductions().copy(
        detail = ProductionDetailUi(
            id = 1L,
            productName = s.SHORT_NAME,
            unit = UNIT,
            quantity = QuantityMilli(10_000),
            totalCost = Money(17_500L),
            date = TODAY,
            note = s.LONG_NAME,
            voided = false,
            lines = listOf(
                ProductionLineUi(s.SECOND_NAME, KILOGRAM, QuantityMilli(2_000), Money(16_000L)),
                ProductionLineUi(s.THIRD_NAME, UNIT, QuantityMilli(10_000), Money(1_500L)),
            ),
            leftIfVoided = QuantityMilli(-4_000),
        ),
    )
}

@ThemePreviews
@Composable
private fun ProductionFormPreview() {
    PreviewTheme { ProductionFormScreen(previewProductionForm(), {}) }
}

@ThemePreviews
@Composable
private fun ProductionFormLotsPreview() {
    PreviewTheme { ProductionFormScreen(previewProductionFormWithLots(), {}) }
}

@ThemePreviews
@Composable
private fun ProductionFormEmptyPreview() {
    PreviewTheme { ProductionFormScreen(ProductionFormUiState(loading = false), {}) }
}

@ThemePreviews
@Composable
private fun ProductionFormNoRecipesPreview() {
    PreviewTheme {
        ProductionFormScreen(ProductionFormUiState(loading = false, noRecipes = true), {})
    }
}

@ThemePreviews
@Composable
private fun ProductionsPreview() {
    PreviewTheme { ProductionsScreen(previewProductions(), {}) }
}

@ThemePreviews
@Composable
private fun ProductionsEmptyPreview() {
    PreviewTheme {
        ProductionsScreen(ProductionsUiState(loading = false, hasRecipes = false), {})
    }
}

@ThemePreviews
@Composable
private fun ProductionDetailPreview() {
    PreviewTheme { ProductionsScreen(previewProductionDetail(), {}) }
}
