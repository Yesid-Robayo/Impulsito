package io.github.yesidrobayo.shopledger.ui.combo

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de combos y recetas, con datos inventados: el kit de la regla, un nombre de 80
// caracteres, un monto de trece cifras, un servicio, un componente archivado, un ciclo y las
// variantes vacías.

private val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
private val KILOGRAM = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)

internal fun previewRecipe(): RecipeUiState {
    val s = PreviewSamples
    return RecipeUiState(
        loading = false,
        comboName = s.LONG_NAME,
        salePrice = Money(5_000L),
        rows = listOf(
            RecipeRowUi(1L, 11L, s.SHORT_NAME, KILOGRAM, "3", Money(900L), true, false),
            RecipeRowUi(2L, 12L, s.LONG_NAME, UNIT, "2", s.LONG_AMOUNT, true, archived = true),
            RecipeRowUi(3L, 13L, s.THIRD_NAME, UNIT, "1", Money(300L), false, false),
            RecipeRowUi(
                4L,
                14L,
                s.SECOND_NAME,
                UNIT,
                "1",
                null,
                true,
                false,
                error = RecipeRowError.Cycle(s.LONG_NAME),
            ),
        ),
        unitCost = Money(1_818L),
        profit = Money(3_182L),
        margin = margin(6_364L),
        available = 4L,
        limitingName = s.SHORT_NAME,
        hasUncosted = true,
        dirty = true,
    )
}

internal fun previewCombos(): CombosUiState {
    val s = PreviewSamples
    return CombosUiState(
        loading = false,
        hasCombos = true,
        items = listOf(
            ComboItemUi(1L, s.SHORT_NAME, 3, 4L, Money(5_000L), margin(6_364L), true),
            ComboItemUi(2L, s.LONG_NAME, 1, null, s.LONG_AMOUNT, Margin.NoData, false),
            ComboItemUi(3L, s.THIRD_NAME, 2, 0L, Money(2_500L), margin(-1_200L), true),
        ),
    )
}

private fun margin(basisPoints: Long): Margin = Margin.Defined(BasisPoints(basisPoints))

@ThemePreviews
@Composable
private fun RecipePreview() {
    PreviewTheme { RecipeScreen(previewRecipe(), {}) }
}

@ThemePreviews
@Composable
private fun RecipeEmptyPreview() {
    PreviewTheme {
        RecipeScreen(RecipeUiState(loading = false, comboName = PreviewSamples.SHORT_NAME), {})
    }
}

@ThemePreviews
@Composable
private fun CombosPreview() {
    PreviewTheme { CombosScreen(previewCombos(), {}) }
}

@ThemePreviews
@Composable
private fun CombosEmptyPreview() {
    PreviewTheme { CombosScreen(CombosUiState(loading = false), {}) }
}
