package io.github.yesidrobayo.shopledger.ui.help

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lo que se sabe del cuerpo de un artículo. */
@Immutable
sealed interface ArticleContent {
    data object Loading : ArticleContent

    data class Ready(val blocks: List<HelpBlock>) : ArticleContent

    /** El archivo no se pudo leer: se dice, y el resto de la ayuda sigue. */
    data object Failed : ArticleContent
}

/**
 * El artículo abierto. [id] es `null` si se pidió uno que no existe, como una clave guardada de
 * otra versión: entonces su contenido es [ArticleContent.Failed].
 */
@Immutable
data class OpenArticle(val id: HelpArticleId?, val content: ArticleContent)

/** Un grupo de la lista, con sus artículos en orden. */
@Immutable
data class HelpListGroup(val group: HelpGroup, val articles: List<HelpArticleId>)

/** Lo que muestra la lista. */
@Immutable
sealed interface HelpList {
    /**
     * Sin búsqueda: [forScreen], que solo existe si se llegó desde una pantalla con más de un
     * artículo, y todos los grupos.
     */
    data class Browse(val forScreen: List<HelpArticleId>, val groups: List<HelpListGroup>) :
        HelpList

    /** Con búsqueda: los artículos que tienen lo escrito en su título o, ya leído, en su texto. */
    data class Search(val results: List<HelpArticleId>) : HelpList
}

/**
 * El estado de `Ayuda`.
 *
 * @property standalone si se pidió un artículo: en un teléfono se ve solo, sin la lista.
 * @property topic la pantalla desde la que se llegó por su enlace de ayuda, o `null`.
 * @property article el artículo abierto, o `null` si solo se ve la lista.
 * @property indexReady si ya se puede buscar en el texto, además de en los títulos.
 */
@Immutable
data class HelpUiState(
    val query: String = "",
    val list: HelpList = HelpList.Browse(emptyList(), emptyList()),
    val standalone: Boolean = false,
    val topic: HelpTopic? = null,
    val article: OpenArticle? = null,
    val indexReady: Boolean = false,
) {
    /**
     * Si el pie de [id] ofrece abrir su pantalla. Llegado desde una pantalla, sus propios
     * artículos no la ofrecen: esa pantalla está justo detrás, y abrirla otra vez la apilaría.
     */
    fun offersTarget(id: HelpArticleId): Boolean =
        id.target() != null && topic?.articles?.contains(id) != true
}

/** Lo que la persona hace en `Ayuda`. */
sealed interface HelpAction {
    data class Search(val query: String) : HelpAction

    /** `Ver todos los artículos`: borra la búsqueda. */
    data object ClearSearch : HelpAction

    /** Muestra [article] junto a la lista, en una ventana ancha. */
    data class Select(val article: HelpArticleId) : HelpAction

    /** Abre [article] en su propia pantalla, en un teléfono. */
    data class Open(val article: HelpArticleId) : HelpAction

    /** Abre la pantalla de la app a la que lleva un artículo. */
    data class OpenTarget(val target: HelpTarget) : HelpAction

    data object ShowSupport : HelpAction

    data object Back : HelpAction
}

/**
 * La ayuda: la lista por grupos, la búsqueda y el artículo abierto. No necesita la base ni la red:
 * todo está en el APK.
 *
 * Los títulos llegan ya escritos en el idioma de la interfaz, así la lista y la búsqueda por
 * título funcionan al instante. El texto de los artículos se lee y se indexa fuera del hilo
 * principal; mientras tanto, la búsqueda solo mira los títulos.
 *
 * @param titles el título de cada artículo, en el idioma de la interfaz.
 * @param read los bloques de un artículo, ya con el nombre de la app, o `null` si no se pudo leer.
 * @param articleKey el artículo pedido, si se pidió uno.
 * @param topicKey la pantalla desde la que se llegó, si se llegó por su enlace de ayuda.
 * @param computation donde se leen y se indexan los artículos.
 */
class HelpViewModel(
    titles: Map<HelpArticleId, String>,
    private val read: suspend (HelpArticleId) -> List<HelpBlock>?,
    articleKey: String?,
    topicKey: String?,
    private val computation: CoroutineContext,
) : ViewModel() {
    private val foldedTitles = titles.mapValues { (_, title) -> TextNormalizer.normalize(title) }
    private val bodies = mutableMapOf<HelpArticleId, List<HelpBlock>>()
    private val foldedBodies = mutableMapOf<HelpArticleId, String>()

    private val state =
        MutableStateFlow(
            HelpUiState(
                standalone = articleKey != null,
                topic = HelpTopic.ofKey(topicKey),
                article = articleKey?.let { key ->
                    val id = HelpArticleId.ofKey(key)
                    val content = if (id == null) ArticleContent.Failed else ArticleContent.Loading
                    OpenArticle(id, content)
                },
            ),
        )
    val uiState: StateFlow<HelpUiState> = state.asStateFlow()

    init {
        state.update { it.copy(list = listFor(it.query, it.topic)) }
        state.value.article?.id?.let(::load)
        viewModelScope.launch {
            // Todos los artículos juntos pesan poco: se leen una vez, y abrir uno ya no espera.
            for (id in HelpArticleId.entries) {
                val blocks = bodies[id] ?: withContext(computation) { read(id) } ?: continue
                bodies[id] = blocks
                foldedBodies[id] = withContext(computation) {
                    TextNormalizer.normalize(HelpMarkdown.plainText(blocks))
                }
            }
            state.update { it.copy(indexReady = true, list = listFor(it.query, it.topic)) }
        }
    }

    fun onAction(action: HelpAction) {
        when (action) {
            is HelpAction.Search -> search(action.query)

            HelpAction.ClearSearch -> search("")

            is HelpAction.Select -> {
                val cached = bodies[action.article]
                state.update {
                    it.copy(
                        article = OpenArticle(
                            action.article,
                            cached?.let(ArticleContent::Ready) ?: ArticleContent.Loading,
                        ),
                    )
                }
                if (cached == null) load(action.article)
            }

            // Las demás las resuelve la navegación.
            is HelpAction.Open,
            is HelpAction.OpenTarget,
            HelpAction.ShowSupport,
            HelpAction.Back,
            -> Unit
        }
    }

    /** Lee [id] y lo muestra, si sigue siendo el artículo abierto cuando termina. */
    private fun load(id: HelpArticleId) {
        viewModelScope.launch {
            val blocks = bodies[id] ?: withContext(computation) { read(id) }
            if (blocks != null) bodies[id] = blocks
            val content = blocks?.let(ArticleContent::Ready) ?: ArticleContent.Failed
            state.update { current ->
                if (current.article?.id == id) {
                    current.copy(article = OpenArticle(id, content))
                } else {
                    current
                }
            }
        }
    }

    private fun search(query: String) {
        state.update { it.copy(query = query, list = listFor(query, it.topic)) }
    }

    /** La lista para [query]: por grupos sin búsqueda, o los artículos que coinciden. */
    private fun listFor(query: String, topic: HelpTopic?): HelpList {
        val folded = TextNormalizer.normalize(query)
        if (folded.isNotEmpty()) {
            return HelpList.Search(
                HelpArticleId.entries.filter { id ->
                    foldedTitles[id]?.contains(folded) == true ||
                        foldedBodies[id]?.contains(folded) == true
                },
            )
        }
        val forScreen = topic?.articles?.takeIf { it.size > 1 }.orEmpty()
        val groups = HelpGroup.entries.map { group ->
            HelpListGroup(group, HelpArticleId.entries.filter { it.group == group })
        }
        return HelpList.Browse(forScreen, groups)
    }
}
