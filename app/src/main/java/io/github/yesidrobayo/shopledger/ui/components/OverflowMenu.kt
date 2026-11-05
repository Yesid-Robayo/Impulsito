package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Una opción del menú. Las destructivas van en el color de error y al final. */
@Immutable
data class OverflowAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * El menú de más opciones: un icono de 48 dp que abre una lista de opciones de 48 dp de alto. Sin
 * sombra: lo separa del fondo un papel distinto y un filo de 1 dp, como a una tarjeta.
 */
@Composable
fun OverflowMenu(actions: List<OverflowAction>, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        TopBarAction(R.drawable.ic_more_vert, stringResource(R.string.a11y_more_options)) {
            open = true
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // Sin elevación tonal ni sombra: el filo y el cambio de papel bastan.
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(AppTheme.sizes.hairline, AppTheme.colors.cardBorder),
        ) {
            OverflowMenuItems(actions) { open = false }
        }
    }
}

@Composable
private fun OverflowMenuItems(actions: List<OverflowAction>, onDone: () -> Unit) {
    val ordered = actions.filterNot { it.destructive } + actions.filter { it.destructive }
    val scheme = MaterialTheme.colorScheme
    ordered.forEach { action ->
        DropdownMenuItem(
            text = {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (action.destructive) scheme.error else scheme.onSurface,
                )
            },
            onClick = {
                onDone()
                action.onClick()
            },
        )
    }
}

@ThemePreviews
@Composable
private fun OverflowMenuPreview() {
    PreviewTheme {
        Box(Modifier.padding(AppTheme.spacing.s8)) {
            OverflowMenu(
                listOf(
                    OverflowAction(stringResource(R.string.action_edit), {}),
                    OverflowAction(stringResource(R.string.status_voided), {}, destructive = true),
                ),
            )
        }
    }
}
