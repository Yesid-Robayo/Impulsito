package io.github.yesidrobayo.shopledger.ui.help

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La ayuda sin Android: la lista por grupos, `Para esta pantalla` solo con varios artículos, la
 * búsqueda sin tildes en títulos y, ya leído, en el texto, y el artículo abierto, también cuando
 * no se puede leer. Los textos son inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelpViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val titles =
        HelpArticleId.entries.associateWith { it.name.lowercase() } +
            (HelpArticleId.BACKUP_LIMITS to "Cómo funciona el respaldo")
    private val bodies =
        HelpArticleId.entries.associateWith { "Texto de ${it.key}." } +
            (HelpArticleId.CLOUD_CHECK to "Mirar la **hora** del archivo en la nube.")
    private val reads = mutableListOf<HelpArticleId>()

    private fun model(
        articleKey: String? = null,
        topicKey: String? = null,
        read: suspend (HelpArticleId) -> List<HelpBlock>? = { id ->
            reads += id
            bodies[id]?.let { HelpMarkdown.parse(it, "Tienda") }
        },
    ) = HelpViewModel(titles, read, articleKey, topicKey, dispatcher)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun `the list has every group in order and nothing for a screen`() {
        val list = model().uiState.value.list as HelpList.Browse

        assertTrue(list.forScreen.isEmpty())
        assertEquals(HelpGroup.entries.toList(), list.groups.map { it.group })
        assertEquals(HelpArticleId.entries.toList(), list.groups.flatMap { it.articles })
    }

    @Test
    fun `a screen with several articles puts them first, and one with a single one does not`() {
        val backup = model(topicKey = HelpTopic.BACKUP.key).uiState.value.list as HelpList.Browse
        val taxes = model(topicKey = HelpTopic.TAXES.key).uiState.value.list as HelpList.Browse

        assertEquals(HelpTopic.BACKUP.articles, backup.forScreen)
        assertTrue(taxes.forScreen.isEmpty())
    }

    @Test
    fun `the search ignores case and accents in titles`() {
        val model = model()

        model.onAction(HelpAction.Search("COMO FUNCIONA"))

        assertEquals(
            HelpList.Search(listOf(HelpArticleId.BACKUP_LIMITS)),
            model.uiState.value.list,
        )
    }

    @Test
    fun `an article named by the search comes before one that only mentions it`() {
        // Negocio va antes que las recetas en la lista, pero solo las nombra en su texto.
        val titled = titles + (HelpArticleId.RECIPES_PRODUCTION to "Recetas y producción")
        val texts = bodies + (HelpArticleId.BUSINESS_HUB to "Combos y recetas, en Negocio.")
        val model =
            HelpViewModel(
                titled,
                { id -> texts[id]?.let { HelpMarkdown.parse(it, "Tienda") } },
                null,
                null,
                dispatcher,
            )

        model.onAction(HelpAction.Search("receta"))

        assertEquals(
            HelpList.Search(listOf(HelpArticleId.RECIPES_PRODUCTION, HelpArticleId.BUSINESS_HUB)),
            model.uiState.value.list,
        )
    }

    @Test
    fun `before the index is ready the search only looks at titles`() {
        val gate = CompletableDeferred<Unit>()
        val waiting: suspend (HelpArticleId) -> List<HelpBlock>? = { id ->
            gate.await()
            bodies[id]?.let { HelpMarkdown.parse(it, "Tienda") }
        }
        val model = model(read = waiting)

        model.onAction(HelpAction.Search("hora del archivo"))
        assertFalse(model.uiState.value.indexReady)
        assertEquals(HelpList.Search(emptyList()), model.uiState.value.list)

        gate.complete(Unit)

        assertTrue(model.uiState.value.indexReady)
        assertEquals(HelpList.Search(listOf(HelpArticleId.CLOUD_CHECK)), model.uiState.value.list)
    }

    @Test
    fun `clearing the search shows every group again`() {
        val model = model()
        model.onAction(HelpAction.Search("zzz"))
        assertEquals(HelpList.Search(emptyList()), model.uiState.value.list)

        model.onAction(HelpAction.ClearSearch)

        assertEquals("", model.uiState.value.query)
        assertTrue(model.uiState.value.list is HelpList.Browse)
    }

    @Test
    fun `an article asked for opens alone with its blocks`() {
        val state = model(articleKey = HelpArticleId.RESTORE.key).uiState.value

        assertTrue(state.standalone)
        assertEquals(HelpArticleId.RESTORE, state.article?.id)
        val paragraph = HelpBlock.Paragraph(listOf(HelpSpan.Plain("Texto de help_restore.")))
        assertEquals(ArticleContent.Ready(listOf(paragraph)), state.article?.content)
    }

    @Test
    fun `an unknown article says it could not be opened`() {
        val state = model(articleKey = "help_from_another_version").uiState.value

        assertEquals(OpenArticle(null, ArticleContent.Failed), state.article)
    }

    @Test
    fun `an article that cannot be read says so, and the rest of the help goes on`() {
        val broken: suspend (HelpArticleId) -> List<HelpBlock>? = { id ->
            if (id == HelpArticleId.TAXES) null else HelpMarkdown.parse("Texto.", "Tienda")
        }
        val model = model(articleKey = HelpArticleId.TAXES.key, read = broken)

        val failed = OpenArticle(HelpArticleId.TAXES, ArticleContent.Failed)
        assertEquals(failed, model.uiState.value.article)
        model.onAction(HelpAction.Select(HelpArticleId.UNITS))
        assertTrue(model.uiState.value.article?.content is ArticleContent.Ready)
    }

    @Test
    fun `selecting an article already read does not read it again`() {
        val model = model()
        val before = reads.count { it == HelpArticleId.LOTS }

        model.onAction(HelpAction.Select(HelpArticleId.LOTS))

        assertEquals(before, reads.count { it == HelpArticleId.LOTS })
        assertTrue(model.uiState.value.article?.content is ArticleContent.Ready)
        assertFalse(model.uiState.value.standalone)
    }

    @Test
    fun `from a screen its own articles do not offer to open it again`() {
        val fromBackup = model(topicKey = HelpTopic.BACKUP.key).uiState.value
        val fromSettings = model().uiState.value

        assertFalse(fromBackup.offersTarget(HelpArticleId.BACKUP_LIMITS))
        assertTrue(fromBackup.offersTarget(HelpArticleId.RECOVERY_CODE))
        assertTrue(fromSettings.offersTarget(HelpArticleId.BACKUP_LIMITS))
        assertFalse(fromSettings.offersTarget(HelpArticleId.GLOSSARY))
    }

    @Test
    fun `navigation actions leave the state as it was`() {
        val model = model()
        val before = model.uiState.value

        model.onAction(HelpAction.Open(HelpArticleId.LOTS))
        model.onAction(HelpAction.ShowSupport)
        model.onAction(HelpAction.Back)

        assertEquals(before, model.uiState.value)
        assertNull(model.uiState.value.article)
    }
}
