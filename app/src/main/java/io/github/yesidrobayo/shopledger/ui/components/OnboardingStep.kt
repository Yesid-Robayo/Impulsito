package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un paso del primer uso: una pregunta por pantalla, sin barra inferior. Arriba el paso y el título;
 * en medio el contenido, que se desplaza; abajo la acción, fija sobre el teclado y la barra del
 * sistema. Volver atrás no pierde lo elegido: ese estado vive en quien lo usa.
 */
@Composable
fun OnboardingStep(
    step: Int,
    totalSteps: Int,
    title: String,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.s16, vertical = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_progress, step, totalSteps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
        Box(
            Modifier
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(spacing.s16),
        ) { action() }
    }
}

@ThemePreviews
@Composable
private fun OnboardingStepPreview() {
    PreviewTheme {
        OnboardingStep(
            step = 2,
            totalSteps = 4,
            title = stringResource(R.string.empty_home_title),
            action = {
                PrimaryActionButton(stringResource(R.string.empty_home_action), {
                }, ActionVariant.PRIMARY)
            },
        ) {
            Text(
                stringResource(R.string.empty_home_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            NoticeCard(PreviewSamples.NOTICE_TEXT, NoticeVariant.INFO)
        }
    }
}
