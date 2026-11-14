package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una impresora guardada: su nombre, el ancho del papel debajo y, si va por defecto, la etiqueta
 * que lo dice. [status] reemplaza al papel mientras imprime, como `Imprimiendo…`. El menú lleva
 * las acciones de la impresora; la fila no tiene toque propio.
 *
 * Solo muestra impresoras ya emparejadas en el teléfono: la app no busca ni empareja.
 */
@Composable
fun PrinterRow(
    name: String,
    paper: String,
    actions: List<OverflowAction>,
    modifier: Modifier = Modifier,
    defaultLabel: String? = null,
    status: String? = null,
) {
    val spacing = AppTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRow)
            .padding(start = spacing.s16, end = spacing.s4, top = spacing.s8, bottom = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                status ?: paper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            defaultLabel?.let { StatusTag(it) }
        }
        OverflowMenu(actions)
    }
}

@ThemePreviews
@Composable
private fun PrinterRowPreview() {
    PreviewTheme {
        Column {
            PrinterRow(
                name = PreviewSamples.SHORT_NAME,
                paper = stringResource(R.string.printer_paper_58),
                actions = listOf(OverflowAction(PreviewSamples.SECOND_NAME, {})),
                defaultLabel = PreviewSamples.THIRD_NAME,
            )
            PrinterRow(
                name = PreviewSamples.LONG_NAME,
                paper = stringResource(R.string.printer_paper_80),
                actions = listOf(OverflowAction(PreviewSamples.SECOND_NAME, {})),
            )
        }
    }
}
