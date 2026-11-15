package io.github.yesidrobayo.shopledger.ui.experience

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: cada herramienta del centro `Negocio` tiene su fila, TalkBack la lee de una vez
 * como botón, con su nombre y para qué sirve, y tocarla abre su pantalla. Busca lo que expone la
 * app a TalkBack, no el texto dibujado.
 */
@RunWith(AndroidJUnit4::class)
class BusinessHubTalkBackTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyToolHasARowThatTalkBackReadsAndThatOpensItsScreen() {
        var opened: AppNavKey? = null
        compose.setContent {
            AppTheme(palette = DefaultColorPalette, darkTheme = false) {
                BusinessHubScreen(
                    // Todos los grupos abiertos: cada herramienta tiene su fila a la vista.
                    BusinessHubUiState(inUse = emptySet(), expanded = ToolGroup.entries.toSet()),
                    rememberHubTools(),
                    onAction = {},
                    onOpen = { opened = it },
                    onBack = {},
                )
            }
        }
        for (entry in HUB_ENTRIES) {
            val spoken =
                context.getString(
                    R.string.a11y_hub_tool,
                    context.getString(entry.name),
                    context.getString(entry.description),
                )
            compose
                .onNode(hasScrollToNodeAction())
                .performScrollToNode(hasContentDescription(spoken))
            compose
                .onNode(hasContentDescription(spoken))
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            compose.waitForIdle()
            assertEquals(entry.tool.name, entry.destination, opened)
        }
    }
}
