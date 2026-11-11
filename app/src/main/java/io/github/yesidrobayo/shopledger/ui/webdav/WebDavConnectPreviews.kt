package io.github.yesidrobayo.shopledger.ui.webdav

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSuggestion
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de `Conectar el servicio`, con datos inventados: vacía con una sugerencia, probando,
// un paso que falló, una contraseña que no sirvió y una tableta.

/** Una sugerencia inventada, con un servicio de ejemplo que no existe. */
internal val PREVIEW_SUGGESTION =
    WebDavSuggestion(
        "example",
        "Servicio de ejemplo",
        "https://dav.example.com/files",
        false,
        false,
    )

internal fun previewSteps(running: WebDavTestStep, failed: Boolean = false): List<StepProgress> =
    WebDavTestStep.entries.map {
        val status =
            when {
                it.ordinal < running.ordinal -> StepStatus.DONE
                it == running -> if (failed) StepStatus.FAILED else StepStatus.RUNNING
                else -> StepStatus.PENDING
            }
        StepProgress(it, status)
    }

internal val PREVIEW_FILLED =
    WebDavConnectUiState(
        suggestions = listOf(PREVIEW_SUGGESTION),
        url = "https://dav.example.com/files",
        username = "copias-tienda",
        password = "inventada-123",
        suggestion = PREVIEW_SUGGESTION,
    )

@ThemePreviews
@Composable
private fun EmptyPreview() {
    PreviewTheme {
        WebDavConnectScreen(
            WebDavConnectUiState(suggestions = listOf(PREVIEW_SUGGESTION)),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun TestingPreview() {
    PreviewTheme {
        WebDavConnectScreen(
            PREVIEW_FILLED.copy(
                phase = WebDavPhase.TESTING,
                steps = previewSteps(WebDavTestStep.MOVE),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun FailedStepPreview() {
    PreviewTheme {
        WebDavConnectScreen(
            PREVIEW_FILLED.copy(
                steps = previewSteps(WebDavTestStep.NO_OVERWRITE, failed = true),
                notice = WebDavNotice.Failed(
                    WebDavTestStep.NO_OVERWRITE,
                    WebDavTestFailure.UNSUPPORTED,
                ),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun RejectedPasswordPreview() {
    PreviewTheme {
        WebDavConnectScreen(
            PREVIEW_FILLED.copy(
                suggestion = null,
                url = "https://dav.example.com",
                urlError = null,
                passwordRejected = true,
                steps = previewSteps(WebDavTestStep.LIST, failed = true),
            ),
            onAction = {},
        )
    }
}

@Preview(name = "Tableta", widthDp = 1024, heightDp = 768)
@Composable
private fun ExpandedPreview() {
    PreviewTheme { WebDavConnectScreen(PREVIEW_FILLED, onAction = {}) }
}
