package io.github.yesidrobayo.shopledger.ui.extras

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Datos inventados para las vistas previas: un campo con un nombre de 80 caracteres, uno de
// un tipo que esta versión no conoce, uno archivado y etiquetas con y sin productos.

private val previewState =
    CustomFieldsUiState(
        loading = false,
        fields =
            listOf(
                CustomField(1L, "Marca", CustomFieldType.TEXT, 0L, false, 12),
                CustomField(2L, "Garantía en meses", CustomFieldType.NUMBER, 1L, false, 1),
                CustomField(3L, "Vence", CustomFieldType.DATE, 2L, false, 0),
                CustomField(4L, PreviewSamples.LONG_NAME, CustomFieldType.BOOLEAN, 3L, false, 0),
                CustomField(5L, "Color", null, 4L, false, 3),
            ),
        archived = listOf(CustomField(6L, "Talla", CustomFieldType.TEXT, 5L, true, 40)),
        tags =
            listOf(
                ProductTag(1L, "Temporada", 18),
                ProductTag(2L, "Para regalo", 1),
                ProductTag(3L, "Nuevo", 0),
            ),
    )

@ThemePreviews
@Composable
private fun CustomFieldsListPreview() {
    PreviewTheme { CustomFieldsScreen(previewState, onAction = {}) }
}

@ThemePreviews
@Composable
private fun CustomFieldsTagsPreview() {
    PreviewTheme {
        CustomFieldsScreen(previewState.copy(tab = CustomFieldsTab.TAGS), onAction = {})
    }
}

@ThemePreviews
@Composable
private fun CustomFieldsEmptyPreview() {
    PreviewTheme { CustomFieldsScreen(CustomFieldsUiState(loading = false), onAction = {}) }
}

@ThemePreviews
@Composable
private fun CustomFieldsTagsEmptyPreview() {
    PreviewTheme {
        CustomFieldsScreen(
            CustomFieldsUiState(loading = false, tab = CustomFieldsTab.TAGS),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun CustomFieldSheetPreview() {
    PreviewTheme {
        CustomFieldsScreen(
            previewState,
            onAction = {},
            editor =
                ExtraEditor(
                    kind = ExtraKind.FIELD,
                    editingId = 1L,
                    name = PreviewSamples.LONG_NAME,
                    type = CustomFieldType.TEXT,
                    savedType = CustomFieldType.TEXT,
                    typeLocked = true,
                    nameProblem = ExtraNameProblem.TOO_LONG,
                ),
        )
    }
}

@ThemePreviews
@Composable
private fun CustomFieldNewSheetPreview() {
    PreviewTheme {
        CustomFieldsScreen(
            previewState,
            onAction = {},
            editor =
                ExtraEditor(
                    kind = ExtraKind.FIELD,
                    editingId = null,
                    name = "Marca",
                    nameProblem = ExtraNameProblem.DUPLICATE,
                ),
        )
    }
}

@ThemePreviews
@Composable
private fun TagDeletePreview() {
    PreviewTheme {
        CustomFieldsScreen(
            previewState.copy(
                tab = CustomFieldsTab.TAGS,
                dialog = CustomFieldsDialog.DeleteTag(1L, "Temporada", 18),
            ),
            onAction = {},
        )
    }
}
