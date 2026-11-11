package io.github.yesidrobayo.shopledger.ui.destination

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileStatus
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de `Preparar archivos en la nube`: vacía, a mitad con un nombre largo, un archivo
// de otra nube, listas, otro destino guardado, cargando y una tableta.

@Composable
private fun Preview(state: SlotSetupUiState) {
    PreviewTheme {
        SlotSetupScreen(state, stringResource(R.string.app_name), onAction = {})
    }
}

@ThemePreviews
@Composable
private fun EmptyPreview() = Preview(SlotSetupSamples.empty)

@ThemePreviews
@Composable
private fun HalfwayLongNamePreview() = Preview(
    SlotSetupUiState(
        SlotSetupSamples.look(
            SlotFileStatus.CREATED,
            SlotFileStatus.MISSING,
            SlotFileStatus.MISSING,
            longName = PreviewSamples.LONG_NAME,
        ),
    ),
)

@ThemePreviews
@Composable
private fun OutsidePreview() = Preview(SlotSetupSamples.outside)

@ThemePreviews
@Composable
private fun ReadyPreview() = Preview(SlotSetupSamples.ready)

@ThemePreviews
@Composable
private fun OtherDestinationPreview() = Preview(SlotSetupSamples.otherDestination)

@ThemePreviews
@Composable
private fun LoadingPreview() = Preview(SlotSetupUiState())

@Preview(name = "Tableta", widthDp = 1024, heightDp = 768)
@Composable
private fun ExpandedPreview() = Preview(SlotSetupSamples.reconnect)
