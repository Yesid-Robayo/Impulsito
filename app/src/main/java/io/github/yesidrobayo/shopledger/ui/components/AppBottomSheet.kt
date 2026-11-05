package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una hoja inferior: el patrón por defecto para elegir o editar sin salir de la pantalla, como la
 * cantidad, una línea, una categoría o el motivo de una anulación.
 *
 * Se cierra deslizando, tocando el velo o con atrás. Mide hasta el 90 % de la pantalla, y lo que no
 * cabe se desplaza; la acción principal queda fija abajo, sobre el teclado. Si tiene cambios, quien la
 * usa pregunta antes de cerrar con `BackHandler`.
 *
 * @param scrollable falso si el contenido ya se desplaza solo, como una lista perezosa.
 * @param action la acción fija de abajo, o `null`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Sin elevación tonal: la jerarquía la da el papel de la hoja.
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
    ) {
        AppBottomSheetContent(title, scrollable, action, content)
    }
}

/**
 * El contenido de la hoja, aparte para poder verlo sin abrirla: el título, lo desplazable y la acción
 * fija.
 */
@Composable
fun AppBottomSheetContent(
    title: String?,
    scrollable: Boolean = true,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = AppTheme.spacing
    val window = LocalWindowInfo.current
    val maxHeight = with(LocalDensity.current) {
        (
            window.containerSize.height *
                AppTheme.sizes.sheetMaxFraction
            ).toDp()
    }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().navigationBarsPadding()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .padding(horizontal = spacing.s16)
                        .padding(bottom = spacing.s8)
                        .semantics { heading() },
            )
        }
        Column(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .then(
                        if (scrollable) {
                            Modifier.verticalScroll(
                                rememberScrollState(),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
            content = content,
        )
        if (action != null) {
            Box(Modifier.padding(spacing.s16)) { action() }
        }
    }
}

@ThemePreviews
@Composable
private fun AppBottomSheetPreview() {
    PreviewTheme {
        Column(Modifier.padding(top = AppTheme.spacing.s16)) {
            AppBottomSheetContent(
                title = stringResource(R.string.form_amount),
                action = {
                    PrimaryActionButton(stringResource(R.string.sale_register), {
                    }, ActionVariant.PRIMARY)
                },
            ) {
                QuantityField("0,250", {
                }, stringResource(R.string.form_amount), PreviewSamples.KILOGRAM)
                MoneyField("4200", {}, stringResource(R.string.form_amount))
            }
        }
    }
}
