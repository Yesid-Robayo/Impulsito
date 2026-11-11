package io.github.yesidrobayo.shopledger.ui.destination

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de `Dónde guardar las copias`: sin destino en el primer uso, con WebDAV conectado,
// la entrega a mano de la nube del teléfono, la hoja `Antes de elegir` y una tableta.

@ThemePreviews
@Composable
private fun NothingConnectedPreview() {
    PreviewTheme {
        DestinationChoiceScreen(
            DestinationChoiceUiState(connected = ConnectedDestination.NONE, firstRun = true),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun WebDavConnectedPreview() {
    PreviewTheme {
        DestinationChoiceScreen(
            DestinationChoiceUiState(connected = ConnectedDestination.WEBDAV),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun ManualSlotsPreview() {
    PreviewTheme {
        DestinationChoiceScreen(
            DestinationChoiceUiState(
                connected = ConnectedDestination.DOCUMENT_SLOTS,
                slotsHandoff = SlotsHandoff.MANUAL,
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun SlotsWarningPreview() {
    PreviewTheme {
        DestinationChoiceScreen(
            DestinationChoiceUiState(connected = ConnectedDestination.NONE, slotsWarning = true),
            onAction = {},
        )
    }
}

@Preview(name = "Tableta", widthDp = 1024, heightDp = 768)
@Composable
private fun ExpandedPreview() {
    PreviewTheme {
        DestinationChoiceScreen(
            DestinationChoiceUiState(connected = ConnectedDestination.NONE),
            onAction = {},
        )
    }
}
