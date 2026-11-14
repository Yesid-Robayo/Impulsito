package io.github.yesidrobayo.shopledger.ui.extra

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de los extras de un producto, con datos inventados: un nombre de 80 caracteres,
// un monto de trece cifras, un extra archivado, el aviso de un cambio que no se guardó y la
// variante vacía.

internal fun previewExtras(): ExtrasUiState {
    val s = PreviewSamples
    return ExtrasUiState(
        loading = false,
        productName = s.SHORT_NAME,
        extras = listOf(
            ExtraRowUi(11L, s.SECOND_NAME, Money(1_000L), archived = false),
            ExtraRowUi(12L, s.LONG_NAME, s.LONG_AMOUNT, archived = false),
            ExtraRowUi(13L, s.THIRD_NAME, Money(500L), archived = true),
        ),
        saveFailed = true,
    )
}

@ThemePreviews
@Composable
private fun ExtrasPreview() {
    PreviewTheme { ExtrasScreen(previewExtras(), {}) }
}

@ThemePreviews
@Composable
private fun ExtrasEmptyPreview() {
    PreviewTheme {
        ExtrasScreen(ExtrasUiState(loading = false, productName = PreviewSamples.LONG_NAME), {})
    }
}
