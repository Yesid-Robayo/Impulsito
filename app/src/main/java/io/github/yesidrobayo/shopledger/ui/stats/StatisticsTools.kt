package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * Los bloques de `Números` que viven en su propio archivo, con su propio estado y su propio
 * ViewModel: la pantalla solo les da el lugar, en el orden de la pantalla, y lo que muestra.
 *
 * Así `StatisticsScreen` sigue pura, y cada bloque se prueba solo. Sin bloques, como en las
 * vistas previas y en las pruebas de la pantalla, no aparece nada.
 *
 * @param analysis `Análisis`, en `Herramientas`, después del flujo proyectado.
 * @param comparison `Comparar`, después de los desgloses y antes de `Herramientas`.
 * @param cashFlow `Flujo proyectado`, en `Herramientas`, después de la ganancia si se repone.
 */
@Immutable
class StatisticsTools(
    val analysis: @Composable ColumnScope.(StatisticsUiState.Content) -> Unit = {},
    val comparison: @Composable ColumnScope.(StatisticsUiState.Content) -> Unit = {},
    val cashFlow: @Composable ColumnScope.(StatisticsUiState.Content) -> Unit = {},
)
