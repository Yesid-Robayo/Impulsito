package io.github.yesidrobayo.shopledger.ui.lot

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas con los lotes inventados de LotSamples: por vencer, vencidos, todos, sin
// asignar, la pantalla vacía, el detalle de un lote y sus hojas de corrección.

@ThemePreviews
@Composable
private fun LotsExpiringPreview() {
    PreviewTheme { LotsScreen(LotSamples.ready(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LotsExpiredPreview() {
    PreviewTheme { LotsScreen(LotSamples.ready(LotFilter.EXPIRED), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LotsAllPreview() {
    PreviewTheme { LotsScreen(LotSamples.ready(LotFilter.ALL), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LotsUnassignedPreview() {
    PreviewTheme { LotsScreen(LotSamples.ready(LotFilter.UNASSIGNED), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LotsEmptyPreview() {
    PreviewTheme { LotsScreen(LotSamples.ready(overview = LotSamples.EMPTY), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LotsLoadingPreview() {
    PreviewTheme { LotsScreen(LotsUiState(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LotDetailPreview() {
    PreviewTheme {
        LotsScreen(
            LotSamples.ready(
                sheet = LotSheet.Detail(
                    LotSamples.SOON.lot.id,
                    LotSamples.MOVEMENTS,
                    LotSamples.MOVEMENTS.last().date,
                ),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun LotFixCodePreview() {
    PreviewTheme {
        LotsScreen(
            LotSamples.ready(
                sheet = LotSheet.FixCode(
                    LotSamples.SOON.lot.id,
                    "A-121",
                    error = LotCodeError.REPEATED,
                    repeatedIn = LotSamples.CHEESE.details.name,
                ),
            ),
            onAction = {},
        )
    }
}
