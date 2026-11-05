package io.github.yesidrobayo.shopledger.ui.placeholder

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El estado de una pantalla que todavía no tiene contenido propio. Son los estados mínimos de toda
 * pantalla: mientras carga, sin nada que mostrar, y si no se pudo leer.
 */
sealed interface PlaceholderUiState {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : PlaceholderUiState

    /** Sin nada que mostrar: el estado vacío con su acción. */
    data object Empty : PlaceholderUiState

    /** No se pudo leer. Nada de lo escrito se pierde, y se puede reintentar. */
    data object ReadError : PlaceholderUiState
}

/** Lo que la persona puede hacer en la pantalla. */
sealed interface PlaceholderAction {
    /** Volver o cerrar, según la barra superior. */
    data object Navigate : PlaceholderAction

    data object OpenSettings : PlaceholderAction

    data object OpenBusiness : PlaceholderAction

    /** La acción del estado vacío. */
    data object EmptyAction : PlaceholderAction

    data object Retry : PlaceholderAction
}

/** El estado vacío de la pantalla, con sus textos ya resueltos. */
data class EmptyContent(
    val title: String,
    val body: String? = null,
    /** La acción del estado vacío, si tiene una. */
    val action: String? = null,
)

/**
 * Lo fijo de la pantalla: título, barra superior y estado vacío.
 *
 * @param withBottomBar si la pantalla es una raíz con la barra inferior encima, que el contenido
 *   no debe tapar.
 * @param businessAction el acceso a Negocio, solo en Inicio, o `null`.
 */
data class PlaceholderContent(
    val title: String,
    val navigation: TopBarNavigation,
    val navigationLabel: String?,
    @param:DrawableRes val icon: Int,
    val empty: EmptyContent,
    val withBottomBar: Boolean,
    val settingsLabel: String? = null,
    val businessAction: String? = null,
)

/**
 * Una pantalla sin contenido propio: su barra superior y sus estados mínimos. No compone ningún dato
 * del negocio; cada pantalla real la reemplaza cuando llega su tarea.
 */
@Composable
fun PlaceholderScreen(
    content: PlaceholderContent,
    uiState: PlaceholderUiState,
    onAction: (PlaceholderAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizes = AppTheme.sizes
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        AppTopBar(
            title = content.title,
            navigation = content.navigation,
            onNavigate = { onAction(PlaceholderAction.Navigate) },
            navigationLabel = content.navigationLabel,
            titleStyle =
                when (content.navigation) {
                    TopBarNavigation.NONE -> MaterialTheme.typography.headlineSmall

                    TopBarNavigation.BACK,
                    TopBarNavigation.CLOSE,
                    -> MaterialTheme.typography.titleLarge
                },
            actions = {
                if (content.settingsLabel != null) {
                    TopBarAction(R.drawable.ic_settings, content.settingsLabel) {
                        onAction(PlaceholderAction.OpenSettings)
                    }
                }
            },
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    // Las raíces dejan libre el alto de la barra inferior, más lo que sobresale Vender.
                    .then(
                        if (content.withBottomBar) {
                            Modifier.padding(bottom = sizes.bottomBar + sizes.sellButtonOverhang)
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (uiState) {
                PlaceholderUiState.Loading -> DelayedSkeleton { LoadingBlocks() }

                PlaceholderUiState.Empty ->
                    EmptyState(
                        icon = content.icon,
                        title = content.empty.title,
                        body = content.empty.body,
                    ) {
                        // Vender manda en las raíces: la acción del estado vacío no compite con el
                        // relleno de marca, y Negocio queda un paso por debajo.
                        content.empty.action?.let { label ->
                            PrimaryActionButton(
                                text = label,
                                onClick = { onAction(PlaceholderAction.EmptyAction) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                        content.businessAction?.let { label ->
                            PrimaryActionButton(
                                text = label,
                                onClick = { onAction(PlaceholderAction.OpenBusiness) },
                                variant = ActionVariant.OUTLINED,
                            )
                        }
                    }

                PlaceholderUiState.ReadError ->
                    EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { onAction(PlaceholderAction.Retry) },
                            variant = ActionVariant.TONAL,
                        )
                    }
            }
        }
    }
}

// Bloques con la forma de un contenido típico: un bloque grande arriba y tres filas.
@Composable
private fun LoadingBlocks() {
    val spacing = AppTheme.spacing
    val sizes = AppTheme.sizes
    Column(
        modifier =
            Modifier
                .widthIn(max = sizes.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = spacing.s16, vertical = spacing.s24),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        SkeletonBlock(Modifier.fillMaxWidth().height(spacing.s64 + spacing.s64))
        repeat(LOADING_ROWS) { SkeletonBlock(Modifier.fillMaxWidth().height(sizes.touchTarget)) }
    }
}

private const val LOADING_ROWS = 3

@Composable
private fun previewContent(withBottomBar: Boolean) = PlaceholderContent(
    title = stringResource(R.string.nav_home),
    navigation = TopBarNavigation.NONE,
    navigationLabel = null,
    icon = R.drawable.ic_home,
    empty =
        EmptyContent(
            title = stringResource(R.string.empty_home_title),
            body = stringResource(R.string.empty_home_body),
            action = stringResource(R.string.empty_home_action),
        ),
    withBottomBar = withBottomBar,
    settingsLabel = stringResource(R.string.a11y_settings),
    businessAction = stringResource(R.string.hub_entry_action),
)

@ThemePreviews
@Composable
private fun PlaceholderEmptyPreview() {
    PreviewTheme {
        PlaceholderScreen(previewContent(withBottomBar = false), PlaceholderUiState.Empty, {})
    }
}

@ThemePreviews
@Composable
private fun PlaceholderErrorPreview() {
    PreviewTheme {
        PlaceholderScreen(previewContent(withBottomBar = false), PlaceholderUiState.ReadError, {})
    }
}

@ThemePreviews
@Composable
private fun PlaceholderLoadingPreview() {
    PreviewTheme { LoadingBlocks() }
}
