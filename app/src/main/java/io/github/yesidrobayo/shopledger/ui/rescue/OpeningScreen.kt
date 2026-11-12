package io.github.yesidrobayo.shopledger.ui.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Lo que se ve mientras la compuerta copia y migra la base, o termina el reemplazo de una
 * restauración: un título, un indicador y una frase, sin cifras ni datos del negocio. Corre con la
 * app en primer plano y fuera del hilo principal; atrás no la interrumpe, porque cerrar la app no
 * corta una migración.
 *
 * Mientras solo abre la base no se ve nada de esto: queda el papel de la paleta, sin destellos.
 */
@Composable
fun OpeningScreen(step: OpeningStage) {
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        if (step == OpeningStage.OPENING) return@Surface
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .padding(AppTheme.spacing.s24),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val (title, body) =
                    when (step) {
                        OpeningStage.MIGRATING ->
                            R.string.migration_title to
                                R.string.migration_body

                        OpeningStage.RESTORING ->
                            R.string.restore_after_restart to R.string.restore_no_exit

                        // Sin texto: se volvió antes.
                        OpeningStage.OPENING -> return@Column
                    }
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun OpeningScreenPreview() {
    PreviewTheme { OpeningScreen(OpeningStage.MIGRATING) }
}
