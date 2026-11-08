package io.github.yesidrobayo.shopledger.ui.experience

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El estado del centro `Negocio`.
 *
 * @property inUse las herramientas en uso, o `null` mientras se leen. Los nombres no esperan a esta
 *   lectura: salen de los recursos y se ven enseguida.
 * @property query lo que se busca. Al buscar, los grupos se ignoran.
 * @property expanded los grupos abiertos que eligió la persona, o `null` si todavía no tocó ninguno:
 *   entonces, en un negocio nuevo, `Dinero` empieza abierto. Vive solo en el estado de la pantalla.
 */
@Immutable
data class BusinessHubUiState(
    val inUse: Set<BusinessTool>? = null,
    val query: String = "",
    val expanded: Set<ToolGroup>? = null,
)

/** Lo que la persona puede hacer en el centro `Negocio`, fuera de abrir una herramienta. */
sealed interface BusinessHubAction {
    data class Search(val query: String) : BusinessHubAction

    data class ToggleGroup(val group: ToolGroup) : BusinessHubAction
}

/**
 * El centro `Negocio`: qué herramientas están en uso y qué grupos están abiertos.
 *
 * Si no se sabe qué herramientas se usan, porque la base no abre o no se lee, todas quedan en
 * `Más herramientas`: ninguna deja de estar a un toque.
 *
 * @param usage las herramientas en uso, o `null` si la base no se pudo abrir.
 */
class BusinessHubViewModel(usage: Flow<Set<BusinessTool>>?) : ViewModel() {
    private val state = MutableStateFlow(
        BusinessHubUiState(
            inUse = if (usage ==
                null
            ) {
                emptySet()
            } else {
                null
            },
        ),
    )
    val uiState: StateFlow<BusinessHubUiState> = state.asStateFlow()

    init {
        if (usage != null) {
            viewModelScope.launch {
                usage.collect { tools -> state.update { it.copy(inUse = tools) } }
            }
        }
    }

    fun onAction(action: BusinessHubAction) {
        when (action) {
            is BusinessHubAction.Search -> state.update { it.copy(query = action.query) }

            is BusinessHubAction.ToggleGroup ->
                state.update {
                    val open = it.openGroups()
                    it.copy(
                        expanded = if (action.group in
                            open
                        ) {
                            open - action.group
                        } else {
                            open + action.group
                        },
                    )
                }
        }
    }
}

/** Los grupos abiertos: los que eligió la persona, o `Dinero` en un negocio que no usa ninguna. */
fun BusinessHubUiState.openGroups(): Set<ToolGroup> =
    expanded ?: if (inUse.isNullOrEmpty()) setOf(ToolGroup.MONEY) else emptySet()

/** Una herramienta ofrecida, con sus textos ya en el idioma de la interfaz. */
@Immutable
data class HubTool(
    val tool: BusinessTool,
    val name: String,
    val description: String,
    val destination: AppNavKey,
) {
    /** Nombre y descripción sin mayúsculas ni tildes, para buscar como escribe la persona. */
    val searchKey: String = TextNormalizer.normalize("$name $description")
}

/** Un grupo de `Más herramientas`, con sus herramientas no usadas. */
@Immutable
data class HubGroup(val group: ToolGroup, val tools: List<HubTool>, val expanded: Boolean)

/** Lo que muestra el centro: las secciones de siempre, o los resultados de una búsqueda. */
sealed interface HubSections {
    /**
     * Sin búsqueda: `En uso`, que no aparece si [inUse] está vacío, y los grupos que tienen alguna
     * herramienta ofrecida. Una herramienta en uso no se repite en su grupo.
     */
    data class Browse(val inUse: List<HubTool>, val groups: List<HubGroup>) : HubSections

    /** Con búsqueda: todas las herramientas que coinciden, en uso o no, sin grupos. */
    data class Search(val results: List<HubTool>) : HubSections
}

/** Las secciones del centro para [state], con las herramientas ofrecidas [tools]. */
fun hubSections(state: BusinessHubUiState, tools: List<HubTool>): HubSections {
    val folded = TextNormalizer.normalize(state.query)
    if (folded.isNotEmpty()) return HubSections.Search(tools.filter { folded in it.searchKey })
    val inUse = state.inUse.orEmpty()
    val open = state.openGroups()
    val groups =
        ToolGroup.entries.mapNotNull { group ->
            val members = tools.filter { it.tool.group == group && it.tool !in inUse }
            if (members.isEmpty()) null else HubGroup(group, members, group in open)
        }
    return HubSections.Browse(tools.filter { it.tool in inUse }, groups)
}
