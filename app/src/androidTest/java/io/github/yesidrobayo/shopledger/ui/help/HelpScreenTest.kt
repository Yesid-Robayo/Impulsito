package io.github.yesidrobayo.shopledger.ui.help

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Ayuda` en el teléfono: la lista con `Para esta pantalla`, la búsqueda sin resultados, el
 * artículo con su pie y el que no se pudo abrir. Lo que se toca llega como acción.
 */
@RunWith(AndroidJUnit4::class)
class HelpScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val app = resources.getString(R.string.app_name)
    private val titles = HelpArticleId.entries.associateWith { resources.getString(it.title, app) }
    private val actions = mutableListOf<HelpAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun browse(topic: HelpTopic? = null) = HelpList.Browse(
        forScreen = topic?.articles?.takeIf { it.size > 1 }.orEmpty(),
        groups = HelpGroup.entries.map { group ->
            HelpListGroup(group, HelpArticleId.entries.filter { it.group == group })
        },
    )

    private fun show(state: HelpUiState) {
        compose.setContent { PreviewTheme { HelpScreen(state, titles) { actions += it } } }
    }

    private fun article(id: HelpArticleId, topic: HelpTopic? = null) = HelpUiState(
        standalone = true,
        topic = topic,
        article = OpenArticle(id, ArticleContent.Ready(HelpMarkdown.parse(BODY, app))),
    )

    @Test
    fun fromAScreenWithSeveralArticlesTheyComeFirst() {
        show(HelpUiState(list = browse(HelpTopic.BACKUP), topic = HelpTopic.BACKUP))

        compose.onNodeWithText(text(R.string.help_section_this_screen)).assertExists()
        compose.onAllNodesWithText(titles.getValue(HelpArticleId.BACKUP_LIMITS))[0].performClick()

        assertEquals(listOf<HelpAction>(HelpAction.Open(HelpArticleId.BACKUP_LIMITS)), actions)
    }

    @Test
    fun theSupportRowIsAtTheEnd() {
        show(HelpUiState(list = browse()))

        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text(R.string.help_support_action)))
        compose.onNodeWithText(text(R.string.help_support_action)).performClick()

        assertTrue(HelpAction.ShowSupport in actions)
    }

    @Test
    fun aSearchWithoutResultsOffersEveryArticle() {
        show(HelpUiState(query = "zzz", list = HelpList.Search(emptyList())))

        val empty = resources.getString(R.string.help_empty_search, "zzz")
        compose.onNodeWithText(empty).assertExists()
        compose.onNodeWithText(text(R.string.help_view_all_action)).performClick()

        assertTrue(HelpAction.ClearSearch in actions)
    }

    @Test
    fun anArticleShowsItsTextItsScreenAndItsRelated() {
        show(article(HelpArticleId.BACKUP_LIMITS))

        compose.onNodeWithText(titles.getValue(HelpArticleId.BACKUP_LIMITS)).assertExists()
        compose.onNodeWithText(BODY).assertExists()
        val open = openBackup()
        compose.onNodeWithText(open).performScrollTo().performClick()
        compose.onNodeWithText(titles.getValue(HelpArticleId.DESTINATIONS))
            .performScrollTo()
            .performClick()

        val target = requireNotNull(HelpArticleId.BACKUP_LIMITS.target())
        assertEquals(
            listOf(HelpAction.OpenTarget(target), HelpAction.Open(HelpArticleId.DESTINATIONS)),
            actions,
        )
    }

    @Test
    fun fromItsOwnScreenAnArticleDoesNotOfferToOpenItAgain() {
        show(article(HelpArticleId.BACKUP_LIMITS, HelpTopic.BACKUP))

        val open = openBackup()
        compose.onNodeWithText(open).assertDoesNotExist()
    }

    @Test
    fun anArticleThatDoesNotOpenSaysSoAndGoesBack() {
        show(HelpUiState(standalone = true, article = OpenArticle(null, ArticleContent.Failed)))

        compose.onNodeWithText(text(R.string.help_error_open)).assertExists()
        compose.onNodeWithText(text(R.string.action_back)).performClick()

        assertEquals(listOf<HelpAction>(HelpAction.Back), actions)
    }

    private fun openBackup() =
        resources.getString(R.string.help_open_screen_action, text(R.string.backup_title))

    private companion object {
        const val BODY = "Las copias salen solas cuando hay cambios."
    }
}
