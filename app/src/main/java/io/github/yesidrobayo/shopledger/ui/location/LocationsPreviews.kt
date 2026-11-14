package io.github.yesidrobayo.shopledger.ui.location

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas con los datos inventados de LocationSamples: la lista, una sola ubicación, el
// stock de una, las transferencias, sus vacíos y el formulario con un aviso de negativo.

@ThemePreviews
@Composable
private fun LocationsListPreview() {
    PreviewTheme { LocationsScreen(LocationSamples.ready(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LocationsSinglePreview() {
    PreviewTheme {
        LocationsScreen(
            LocationsUiState(content = LocationsContent.Ready(LocationSamples.SINGLE)),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun LocationDetailPreview() {
    PreviewTheme {
        LocationsScreen(
            LocationSamples.ready().copy(
                selectedId = LocationSamples.STALL.id,
                detail = LocationDetailContent.Ready(
                    LocationSamples.STALL,
                    LocationSamples.ITEMS,
                    hasStock = true,
                ),
                archiveBlocked = true,
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun LocationTransfersPreview() {
    PreviewTheme { LocationsScreen(LocationSamples.ready(LocationsTab.TRANSFERS), onAction = {}) }
}

@ThemePreviews
@Composable
private fun LocationTransfersEmptyPreview() {
    PreviewTheme {
        LocationsScreen(
            LocationSamples.ready(LocationsTab.TRANSFERS).copy(
                transfers = TransfersContent.Ready(emptyList(), false, false),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun NewTransferPreview() {
    PreviewTheme {
        NewTransferScreen(
            NewTransferUiState(
                loading = false,
                locations = listOf(LocationSamples.MAIN, LocationSamples.STALL),
                fromId = LocationSamples.MAIN.id,
                toId = LocationSamples.STALL.id,
                lines = listOf(
                    TransferLineUi(1L, 7L, PreviewSamples.SHORT_NAME, LocationSamples.UNIT, "4"),
                    TransferLineUi(
                        2L,
                        8L,
                        PreviewSamples.LONG_NAME,
                        LocationSamples.KILOGRAM,
                        "12,5",
                        leftAtOrigin = QuantityMilli(-2_350L),
                    ),
                ),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun NewTransferEmptyPreview() {
    PreviewTheme {
        NewTransferScreen(
            NewTransferUiState(
                loading = false,
                locations = listOf(LocationSamples.MAIN, LocationSamples.STALL),
                fromId = LocationSamples.MAIN.id,
                toId = LocationSamples.MAIN.id,
                endError = TransferEnd.TO,
                showNoLines = true,
            ),
            onAction = {},
        )
    }
}
