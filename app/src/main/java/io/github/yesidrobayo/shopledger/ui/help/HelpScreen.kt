package io.github.yesidrobayo.shopledger.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SearchResultCount
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * `Ayuda`: el soporte de una app sin soporte humano. Arriba la búsqueda, luego `Para esta
 * pantalla` si se llegó desde una con varios artículos, los grupos y, al final, `Información para
 * soporte`. No tiene acción principal: es una pantalla de lectura, y funciona sin internet ni
 * base.
 *
 * En un teléfono, la lista y el artículo son pantallas aparte. Desde 840 dp, la lista queda a la
 * izquierda y el artículo a la derecha, con un ancho de lectura de 600 dp como máximo.
 *
 * Es pura: recibe el estado y avisa cada acción. Abrir un artículo en un teléfono es
 * [HelpAction.Open]; junto a la lista, [HelpAction.Select].
 *
 * @param titles el título de cada artículo, en el idioma de la interfaz.
 */
@Composable
fun HelpScreen(
    uiState: HelpUiState,
    titles: Map<HelpArticleId, String>,
    onAction: (HelpAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.help_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(HelpAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= EXPANDED_WIDTH) {
                // Junto a la lista siempre hay un artículo: el pedido, o el primero que se ofrece.
                val shown = uiState.article?.id ?: firstOf(uiState.list)
                val missing = uiState.article == null
                LaunchedEffect(shown, missing) {
                    if (missing && shown != null) onAction(HelpAction.Select(shown))
                }
                Row(Modifier.fillMaxSize()) {
                    ArticleList(
                        uiState,
                        titles,
                        selected = shown,
                        onOpen = { onAction(HelpAction.Select(it)) },
                        onAction = onAction,
                        modifier = Modifier.weight(1f),
                    )
                    ArticlePane(
                        uiState,
                        uiState.article,
                        titles,
                        onOpen = { onAction(HelpAction.Select(it)) },
                        onAction = onAction,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (uiState.standalone) {
                ArticlePane(
                    uiState,
                    uiState.article,
                    titles,
                    onOpen = { onAction(HelpAction.Open(it)) },
                    onAction = onAction,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ArticleList(
                    uiState,
                    titles,
                    selected = null,
                    onOpen = { onAction(HelpAction.Open(it)) },
                    onAction = onAction,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** El primer artículo que ofrece la lista, para no dejar vacío el panel del artículo. */
private fun firstOf(list: HelpList): HelpArticleId? = when (list) {
    is HelpList.Browse ->
        list.forScreen.firstOrNull() ?: list.groups.firstOrNull()?.articles?.firstOrNull()

    is HelpList.Search -> list.results.firstOrNull()
}

@Composable
private fun ArticleList(
    uiState: HelpUiState,
    titles: Map<HelpArticleId, String>,
    selected: HelpArticleId?,
    onOpen: (HelpArticleId) -> Unit,
    onAction: (HelpAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()) {
            SearchField(
                query = uiState.query,
                onQueryChange = { onAction(HelpAction.Search(it)) },
                placeholder = stringResource(R.string.help_search_hint),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
            val search = uiState.list as? HelpList.Search
            if (search != null && uiState.query.isNotBlank()) {
                val found = search.results.size
                SearchResultCount(
                    pluralStringResource(R.plurals.a11y_help_search_results, found, found),
                    Modifier.padding(
                        horizontal = AppTheme.spacing.s16,
                        vertical = AppTheme.spacing.s4,
                    ),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = AppTheme.spacing.s24),
            ) {
                when (val list = uiState.list) {
                    is HelpList.Browse -> browse(list, titles, selected, onOpen)

                    is HelpList.Search ->
                        searchResults(list, uiState.query, titles, selected, onOpen, onAction)
                }
                item(key = SUPPORT_KEY) {
                    SettingsRow(
                        title = stringResource(R.string.help_support_action),
                        summary = stringResource(R.string.support_help),
                        action = SettingsRowAction.Navigation { onAction(HelpAction.ShowSupport) },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.browse(
    list: HelpList.Browse,
    titles: Map<HelpArticleId, String>,
    selected: HelpArticleId?,
    onOpen: (HelpArticleId) -> Unit,
) {
    if (list.forScreen.isNotEmpty()) {
        item(key = FOR_SCREEN_KEY) {
            SectionHeader(stringResource(R.string.help_section_this_screen))
        }
        items(list.forScreen, key = { "screen-${it.key}" }) { id ->
            ArticleRow(id, titles, id == selected, onOpen)
        }
    }
    list.groups.forEach { group ->
        item(key = "group-${group.group.name}") {
            SectionHeader(stringResource(group.group.title))
        }
        items(group.articles, key = { it.key }) { id ->
            ArticleRow(id, titles, id == selected, onOpen)
        }
    }
}

private fun LazyListScope.searchResults(
    list: HelpList.Search,
    query: String,
    titles: Map<HelpArticleId, String>,
    selected: HelpArticleId?,
    onOpen: (HelpArticleId) -> Unit,
    onAction: (HelpAction) -> Unit,
) {
    if (list.results.isEmpty()) {
        item(key = EMPTY_SEARCH_KEY) {
            EmptyStateInline(
                title = stringResource(R.string.help_empty_search, query.trim()),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                action = {
                    InlineTextButton(stringResource(R.string.help_view_all_action), {
                        onAction(HelpAction.ClearSearch)
                    })
                },
            )
        }
    }
    items(list.results, key = { it.key }) { id ->
        ArticleRow(id, titles, id == selected, onOpen)
    }
}

/** Una fila de artículo: toda la fila se toca. Junto al artículo abierto, la suya se marca. */
@Composable
private fun ArticleRow(
    id: HelpArticleId,
    titles: Map<HelpArticleId, String>,
    selected: Boolean,
    onOpen: (HelpArticleId) -> Unit,
) {
    SettingsRow(
        title = titles[id].orEmpty(),
        action = SettingsRowAction.Navigation { onOpen(id) },
        modifier =
            if (selected) {
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .semantics { this.selected = true }
            } else {
                Modifier
            },
    )
}

/**
 * El artículo y su pie: la acción hacia su pantalla, si la tiene, y `Relacionados`. Si no se pudo
 * abrir, lo dice con `Volver`, y el resto de la ayuda sigue.
 */
@Composable
private fun ArticlePane(
    uiState: HelpUiState,
    article: OpenArticle?,
    titles: Map<HelpArticleId, String>,
    onOpen: (HelpArticleId) -> Unit,
    onAction: (HelpAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val id = article?.id
        when (val content = article?.content) {
            null -> Unit

            ArticleContent.Loading -> DelayedSkeleton { ArticleSkeleton() }

            ArticleContent.Failed ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.help_error_open),
                ) {
                    PrimaryActionButton(
                        stringResource(R.string.action_back),
                        { onAction(HelpAction.Back) },
                        ActionVariant.TONAL,
                    )
                }

            is ArticleContent.Ready ->
                Column(
                    Modifier
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = spacing.s24),
                ) {
                    HelpArticle(
                        title = id?.let { titles[it] }.orEmpty(),
                        blocks = content.blocks,
                        onOpenArticle = onOpen,
                        modifier =
                            Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    )
                    if (id != null) ArticleFooter(uiState, id, titles, onOpen, onAction)
                }
        }
    }
}

@Composable
private fun ArticleFooter(
    uiState: HelpUiState,
    id: HelpArticleId,
    titles: Map<HelpArticleId, String>,
    onOpen: (HelpArticleId) -> Unit,
    onAction: (HelpAction) -> Unit,
) {
    val target = id.target()
    if (target != null && uiState.offersTarget(id)) {
        PrimaryActionButton(
            stringResource(R.string.help_open_screen_action, stringResource(target.screen)),
            { onAction(HelpAction.OpenTarget(target)) },
            ActionVariant.TONAL,
            modifier =
                Modifier.padding(
                    horizontal = AppTheme.spacing.s16,
                    vertical = AppTheme.spacing.s8,
                ),
        )
    }
    val related = id.related()
    if (related.isNotEmpty()) {
        SectionHeader(stringResource(R.string.help_related))
        related.forEach { other ->
            SettingsRow(
                title = titles[other].orEmpty(),
                action = SettingsRowAction.Navigation { onOpen(other) },
            )
        }
    }
}

/** Mientras se lee un artículo, tras 200 ms: un título y unas líneas. */
@Composable
private fun ArticleSkeleton() {
    Column(
        Modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        SkeletonBlock(Modifier.fillMaxWidth(SKELETON_TITLE_WIDTH).height(AppTheme.spacing.s32))
        repeat(SKELETON_LINES) {
            SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.spacing.s16))
        }
    }
}

// Desde 840 dp, la lista a la izquierda y el artículo a la derecha.
private val EXPANDED_WIDTH = 840.dp

private const val SKELETON_TITLE_WIDTH = 0.6f
private const val SKELETON_LINES = 5
private const val SUPPORT_KEY = "support"
private const val FOR_SCREEN_KEY = "for-screen"
private const val EMPTY_SEARCH_KEY = "search-empty"

/** Los títulos de muestra, como los da la pantalla, para las vistas previas. */
@Composable
private fun previewTitles(): Map<HelpArticleId, String> =
    HelpArticleId.entries.associateWith { helpTitle(it) }

private fun previewBrowse(topic: HelpTopic? = null): HelpList.Browse = HelpList.Browse(
    forScreen = topic?.articles?.takeIf { it.size > 1 }.orEmpty(),
    groups = HelpGroup.entries.map { group ->
        HelpListGroup(group, HelpArticleId.entries.filter { it.group == group })
    },
)

@ThemePreviews
@Composable
private fun HelpListPreview() {
    PreviewTheme {
        HelpScreen(
            HelpUiState(list = previewBrowse(HelpTopic.BACKUP), topic = HelpTopic.BACKUP),
            previewTitles(),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun HelpArticlePanePreview() {
    val blocks = HelpMarkdown.parse(PREVIEW_ARTICLE, stringResource(R.string.app_name))
    PreviewTheme {
        HelpScreen(
            HelpUiState(
                standalone = true,
                article = OpenArticle(HelpArticleId.BACKUP_LIMITS, ArticleContent.Ready(blocks)),
            ),
            previewTitles(),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun HelpSearchEmptyPreview() {
    PreviewTheme {
        HelpScreen(
            HelpUiState(query = "zzz", list = HelpList.Search(emptyList())),
            previewTitles(),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun HelpArticleFailedPreview() {
    PreviewTheme {
        HelpScreen(
            HelpUiState(standalone = true, article = OpenArticle(null, ArticleContent.Failed)),
            previewTitles(),
            {},
        )
    }
}
