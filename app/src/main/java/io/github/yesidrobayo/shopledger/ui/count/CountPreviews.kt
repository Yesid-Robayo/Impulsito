package io.github.yesidrobayo.shopledger.ui.count

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas con los datos inventados de CountSamples: el inicio con conteos, vacío, la hoja
// de uno nuevo, la sesión a ciegas y con lo esperado, la revisión y un conteo ya aplicado.

@ThemePreviews
@Composable
private fun CountHomePreview() {
    PreviewTheme {
        CountHomeScreen(
            CountHomeUiState(content = CountHomeContent.Ready(CountSamples.OVERVIEW)),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun CountHomeEmptyPreview() {
    PreviewTheme {
        CountHomeScreen(
            CountHomeUiState(content = CountHomeContent.Ready(CountSamples.EMPTY)),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun CountNewSheetPreview() {
    PreviewTheme {
        CountHomeScreen(
            CountHomeUiState(
                content = CountHomeContent.Ready(CountSamples.OVERVIEW),
                sheet = NewCountSheet(
                    locationId = CountSamples.OPEN.locationId,
                    categories = listOf(CountSamples.CATEGORY),
                    error = NewCountError.SCOPE_EMPTY,
                ),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun CountSessionBlindPreview() {
    PreviewTheme { CountSessionScreen(CountSamples.counting(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun CountSessionExpectedPreview() {
    PreviewTheme { CountSessionScreen(CountSamples.counting(blind = false), onAction = {}) }
}

@ThemePreviews
@Composable
private fun CountReviewPreview() {
    PreviewTheme { CountSessionScreen(CountSamples.reviewing(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun CountReviewDiscreetPreview() {
    PreviewTheme(discreet = true) {
        CountSessionScreen(
            CountSamples.reviewing().copy(dialog = CountDialog.APPLY),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun CountAppliedPreview() {
    PreviewTheme {
        CountSessionScreen(
            CountSamples.counting().copy(session = CountSamples.APPLIED),
            onAction = {},
        )
    }
}
