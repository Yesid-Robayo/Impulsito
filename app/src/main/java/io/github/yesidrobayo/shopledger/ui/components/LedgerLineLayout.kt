package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.max

/**
 * La forma de una fila de libreta: el texto a la izquierda y las cifras a la derecha. Si las cifras
 * piden más de dos quintos del ancho, como con letra grande o un monto de 13 cifras, pasan debajo del
 * texto, alineadas a la izquierda: así el nombre nunca queda en una columna de dos letras y el monto
 * nunca se corta.
 *
 * Decide con lo que mide de verdad cada parte, no con la escala de la letra, que crece de forma
 * distinta en cada tamaño.
 *
 * @param trailing recibe la alineación que le toca: `End` al lado del texto, `Start` debajo.
 * @param trailingWraps si la parte derecha es una frase que puede pasar a dos líneas, como
 *   `Total estimado 18.500`: entonces se parte a la derecha en dos líneas, la palabra y la cifra, y
 *   solo si así no cabe baja entera bajo el texto.
 */
@Composable
fun LedgerLineLayout(
    gap: Dp,
    modifier: Modifier = Modifier,
    trailingWraps: Boolean = false,
    leading: @Composable () -> Unit,
    trailing: @Composable (Alignment.Horizontal) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val probe = subcompose(Slot.PROBE) { trailing(Alignment.End) }
        val fullWidth = probe.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val share = if (width ==
            Constraints.Infinity
        ) {
            fullWidth
        } else {
            width * TRAILING_SHARE / SIDE_BY_SIDE_PARTS
        }
        // Una frase que se parte cabe al lado si su palabra más larga cabe en la parte que le toca.
        val trailingWidth =
            if (trailingWraps) {
                val longestWord =
                    probe.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0
                val oneLine = probe.maxOfOrNull { it.maxIntrinsicHeight(Constraints.Infinity) } ?: 0
                val wrapped = probe.maxOfOrNull { it.maxIntrinsicHeight(share) } ?: 0
                // Al lado solo en dos líneas a lo sumo: la palabra arriba y la cifra abajo. Más, y la
                // frase se vería partida palabra por palabra. Se compara con dos líneas y media,
                // porque el alto de una línea sola viene recortado arriba y abajo.
                val fits = longestWord <= share &&
                    wrapped * HALF_LINES < oneLine * (MAX_WRAPPED_LINES * HALF_LINES + 1)
                if (fits) minOf(fullWidth, share) else fullWidth
            } else {
                fullWidth
            }
        val sideBySide =
            width == Constraints.Infinity ||
                trailingWidth * SIDE_BY_SIDE_PARTS <= width * TRAILING_SHARE
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (sideBySide) {
            val trailingPlaceables =
                subcompose(Slot.TRAILING) { trailing(Alignment.End) }.map {
                    it.measure(loose.copy(maxWidth = trailingWidth))
                }
            val shownTrailing = trailingPlaceables.maxOfOrNull { it.width } ?: 0
            val leadingMax =
                if (width ==
                    Constraints.Infinity
                ) {
                    Constraints.Infinity
                } else {
                    max(
                        0,
                        width - shownTrailing - gapPx,
                    )
                }
            val leadingPlaceables =
                subcompose(Slot.LEADING, leading).map {
                    it.measure(loose.copy(maxWidth = leadingMax))
                }
            val height =
                max(
                    max(
                        leadingPlaceables.maxOfOrNull { it.height } ?: 0,
                        trailingPlaceables.maxOfOrNull { it.height } ?: 0,
                    ),
                    constraints.minHeight,
                )
            val layoutWidth =
                if (width == Constraints.Infinity) {
                    (leadingPlaceables.maxOfOrNull { it.width } ?: 0) + gapPx + shownTrailing
                } else {
                    width
                }
            layout(layoutWidth, height) {
                leadingPlaceables.forEach { it.placeRelative(0, 0) }
                trailingPlaceables.forEach { it.placeRelative(layoutWidth - it.width, 0) }
            }
        } else {
            val leadingPlaceables = subcompose(Slot.LEADING, leading).map { it.measure(loose) }
            val trailingPlaceables =
                subcompose(Slot.TRAILING) { trailing(Alignment.Start) }.map { it.measure(loose) }
            val leadingHeight = leadingPlaceables.maxOfOrNull { it.height } ?: 0
            val stackGap = gapPx / 2
            val height =
                max(
                    leadingHeight + stackGap + (
                        trailingPlaceables.maxOfOrNull {
                            it.height
                        } ?: 0
                        ),
                    constraints.minHeight,
                )
            layout(width, height) {
                leadingPlaceables.forEach { it.placeRelative(0, 0) }
                trailingPlaceables.forEach { it.placeRelative(0, leadingHeight + stackGap) }
            }
        }
    }
}

private enum class Slot { PROBE, LEADING, TRAILING }

// Las cifras van al lado del texto si piden hasta dos quintos del ancho: más, y el nombre quedaría
// partido en una columna angosta.
private const val TRAILING_SHARE = 2
private const val SIDE_BY_SIDE_PARTS = 5

// Una frase de la derecha que se parte ocupa a lo sumo dos líneas al lado del texto.
private const val MAX_WRAPPED_LINES = 2
private const val HALF_LINES = 2
