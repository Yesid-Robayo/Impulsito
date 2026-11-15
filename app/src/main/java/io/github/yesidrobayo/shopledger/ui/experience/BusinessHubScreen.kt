package io.github.yesidrobayo.shopledger.ui.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SearchResultCount
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.ToolGroupRow
import io.github.yesidrobayo.shopledger.ui.components.ToolRow
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El centro `Negocio`: lo que no se usa a diario, sin agregar pestañas. Arriba lo que ya está en
 * uso; debajo, el resto por grupos plegados; al final, la ayuda. No tiene acción principal: cada
 * fila abre su herramienta.
 *
 * Es pura: recibe el estado y las herramientas ofrecidas, y avisa cada acción.
 *
 * @param onOpen abre la pantalla de una herramienta o la ayuda.
 */
@Composable
fun BusinessHubScreen(
    uiState: BusinessHubUiState,
    tools: List<HubTool>,
    onAction: (BusinessHubAction) -> Unit,
    onOpen: (AppNavKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(uiState, tools) { hubSections(uiState, tools) }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.hub_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = onBack,
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()) {
                SearchField(
                    query = uiState.query,
                    onQueryChange = { onAction(BusinessHubAction.Search(it)) },
                    placeholder = stringResource(R.string.hub_search_hint),
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                )
                if (sections is HubSections.Search) {
                    val found = sections.results.size
                    SearchResultCount(
                        pluralStringResource(R.plurals.a11y_hub_search_results, found, found),
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
                    when (sections) {
                        is HubSections.Search -> searchResults(sections, uiState.query, onOpen)

                        is HubSections.Browse -> browse(
                            sections,
                            uiState.inUse != null,
                            onAction,
                            onOpen,
                        )
                    }
                    item(key = HELP_KEY) {
                        SettingsRow(
                            title = stringResource(R.string.hub_help_row),
                            action = SettingsRowAction.Navigation { onOpen(AppNavKey.Help()) },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.searchResults(
    sections: HubSections.Search,
    query: String,
    onOpen: (AppNavKey) -> Unit,
) {
    if (sections.results.isEmpty()) {
        item(key = EMPTY_SEARCH_KEY) {
            EmptyStateInline(
                title = stringResource(R.string.hub_search_empty, query.trim()),
                modifier = Modifier.padding(AppTheme.spacing.s16),
            )
        }
    }
    items(sections.results, key = { it.tool.name }) { tool -> Tool(tool, onOpen) }
}

private fun LazyListScope.browse(
    sections: HubSections.Browse,
    usageKnown: Boolean,
    onAction: (BusinessHubAction) -> Unit,
    onOpen: (AppNavKey) -> Unit,
) {
    if (sections.inUse.isNotEmpty()) {
        item(key = IN_USE_KEY) { SectionHeader(stringResource(R.string.hub_section_in_use)) }
        items(sections.inUse, key = { "in-use-${it.tool.name}" }) { tool -> Tool(tool, onOpen) }
    }
    if (sections.groups.isEmpty()) return
    item(key = MORE_KEY) { SectionHeader(stringResource(R.string.hub_section_more)) }
    // Un negocio que todavía no usa ninguna herramienta lee cómo llegan a En uso.
    if (usageKnown && sections.inUse.isEmpty()) {
        item(key = FIRST_USE_KEY) {
            Text(
                stringResource(R.string.hub_first_use_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = AppTheme.spacing.s16,
                ).padding(bottom = AppTheme.spacing.s8),
            )
        }
    }
    sections.groups.forEach { group ->
        item(key = "group-${group.group.name}") {
            ToolGroupRow(
                title = stringResource(groupTitle(group.group)),
                count = group.tools.size,
                expanded = group.expanded,
                onToggle = { onAction(BusinessHubAction.ToggleGroup(group.group)) },
            )
        }
        if (group.expanded) {
            items(group.tools, key = { it.tool.name }) { tool -> Tool(tool, onOpen) }
        }
    }
}

@Composable
private fun Tool(tool: HubTool, onOpen: (AppNavKey) -> Unit) {
    ToolRow(name = tool.name, detail = tool.description, onClick = { onOpen(tool.destination) })
}

/** Las herramientas ofrecidas con sus textos en el idioma de la interfaz. */
@Composable
fun rememberHubTools(entries: List<HubEntry> = HUB_ENTRIES): List<HubTool> {
    val names = entries.map { stringResource(it.name) to stringResource(it.description) }
    return remember(entries, names) {
        entries.mapIndexed { index, entry ->
            HubTool(entry.tool, names[index].first, names[index].second, entry.destination)
        }
    }
}

private const val HELP_KEY = "help"
private const val EMPTY_SEARCH_KEY = "search-empty"
private const val IN_USE_KEY = "in-use"
private const val MORE_KEY = "more"
private const val FIRST_USE_KEY = "first-use"

/** Herramientas de muestra con el nombre de 80 caracteres, en dos grupos. */
@Composable
private fun previewTools(): List<HubTool> = listOf(
    HubTool(
        BusinessTool.CUSTOMERS,
        PreviewSamples.TOOL_NAME,
        PreviewSamples.TOOL_DETAIL,
        AppNavKey.Customers(),
    ),
    HubTool(
        BusinessTool.UNITS,
        stringResource(R.string.hub_tool_units),
        stringResource(R.string.hub_tool_units_desc),
        AppNavKey.Units,
    ),
    HubTool(
        BusinessTool.LOTS,
        PreviewSamples.LONG_NAME,
        stringResource(R.string.hub_tool_units_desc),
        AppNavKey.Lots(),
    ),
)

@ThemePreviews
@Composable
private fun BusinessHubNewPreview() {
    PreviewTheme {
        BusinessHubScreen(BusinessHubUiState(inUse = emptySet()), previewTools(), {}, {}, {})
    }
}

@ThemePreviews
@Composable
private fun BusinessHubInUsePreview() {
    PreviewTheme {
        BusinessHubScreen(
            BusinessHubUiState(
                inUse = setOf(BusinessTool.CUSTOMERS),
                expanded = setOf(ToolGroup.CATALOG_AND_INVENTORY),
            ),
            previewTools(),
            {},
            {},
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun BusinessHubSearchPreview() {
    PreviewTheme {
        BusinessHubScreen(BusinessHubUiState(inUse = emptySet(), query = "zzz"), previewTools(), {
        }, {}, {})
    }
}
