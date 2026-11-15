package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Si la app muestra la demostración: lo que depende de la nube se desactiva, con la razón. Lo da
 * la actividad principal; sin ella, como en las pruebas de cada pantalla, vale `false`.
 */
val LocalDemoMode = staticCompositionLocalOf { false }

/**
 * La banda fija del modo demostración, bajo la barra de estado: `Datos de ejemplo`, que lo
 * registrado ahí no se guarda, y `Salir`. Nunca se confunde con datos reales: va en el contenedor
 * terciario, con su icono, y se anuncia al aparecer.
 *
 * Toma el alto de la barra de estado. Lo que va debajo ya no lo cuenta: lo dibuja [DemoFrame].
 *
 * @param exiting si ya se pidió salir: `Salir` no se repite mientras la app se reinicia.
 */
@Composable
fun DemoBanner(onExit: () -> Unit, modifier: Modifier = Modifier, exiting: Boolean = false) {
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = content,
    ) {
        Row(
            modifier =
                Modifier
                    .statusBarsPadding()
                    .heightIn(min = AppTheme.sizes.touchTarget)
                    .padding(start = AppTheme.spacing.s16, end = AppTheme.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_science),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(AppTheme.sizes.iconInline),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = AppTheme.spacing.s4)
                        // Se anuncia al entrar: lo que sigue son datos de ejemplo.
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        },
            ) {
                Text(
                    text = stringResource(R.string.demo_banner_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
                Text(
                    text = stringResource(R.string.demo_not_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
            InlineTextButton(
                text = stringResource(R.string.demo_banner_exit),
                onClick = { if (!exiting) onExit() },
                color = content,
            )
        }
    }
}

/**
 * Pone [DemoBanner] encima de toda la app mientras [active]: también sobre las pantallas de
 * pantalla completa, el bloqueo y el primer uso. Lo de debajo recibe la barra de estado ya
 * descontada, así no deja otro hueco arriba. Sin demostración, dibuja [content] tal cual.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoFrame(
    active: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    exiting: Boolean = false,
    content: @Composable () -> Unit,
) {
    // La app va siempre en el mismo lugar del árbol: si la banda cambiara, nada se pierde.
    Column(modifier.fillMaxSize()) {
        if (active) DemoBanner(onExit = onExit, exiting = exiting)
        val below =
            if (active) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Box(Modifier.weight(1f).then(below)) { content() }
    }
}

@ThemePreviews
@Composable
private fun DemoBannerPreview() {
    PreviewTheme {
        DemoFrame(active = true, onExit = {}) {
            Box(Modifier.fillMaxSize().padding(AppTheme.spacing.s16)) {
                NoticeCard(PreviewSamples.NOTICE_TEXT, NoticeVariant.INFO)
            }
        }
    }
}
