package io.github.yesidrobayo.shopledger.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationNotice
import io.github.yesidrobayo.shopledger.ui.backup.previewBackupState
import io.github.yesidrobayo.shopledger.ui.backup.previewOverview
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inicio en el teléfono: cada acceso avisa su acción, el primer uso lleva a crear un producto, el
 * día sin ventas lo dice, el modo discreto oculta el dinero y deja el número de ventas, el error
 * deja reintentar, y el aviso sin destino se acepta y se reduce a una línea. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<HomeAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: HomeUiState, discreet: Boolean = false) {
        compose.setContent {
            PreviewTheme(discreet = discreet) { HomeScreen(state, { actions += it }) }
        }
    }

    @Test
    fun everyEntryReportsItsAction() {
        show(previewHomeContent())

        compose.onNodeWithContentDescription(text(R.string.a11y_settings)).performClick()
        compose.onNodeWithText(text(R.string.hub_entry_action)).performClick()
        compose.onNodeWithText(text(R.string.action_view_all_feminine)).performClick()
        compose.onNodeWithText(PreviewSamples.SECOND_NAME).performClick()

        assertEquals(
            listOf(
                HomeAction.OpenSettings,
                HomeAction.OpenBusiness,
                HomeAction.OpenAllSales,
                HomeAction.OpenSale(5L),
            ),
            actions,
        )
    }

    @Test
    fun theDayShowsItsSalesProfitAndMargin() {
        show(previewHomeContent())

        compose.onNodeWithText(text(R.string.home_sales_today)).assertIsDisplayed()
        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.stats_sales_count, 12, 12),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.term_sales_profit)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.term_margin)).assertIsDisplayed()
    }

    @Test
    fun theFirstUseLeadsToCreateTheFirstProduct() {
        show(
            previewHomeContent().copy(
                hero = TodayHero.NoSalesYet,
                firstUse = true,
                recentSales = emptyList(),
            ),
        )

        compose.onNodeWithText(text(R.string.empty_home_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.empty_home_action)).performClick()

        assertEquals(listOf<HomeAction>(HomeAction.CreateFirstProduct), actions)
        // Sin ventas nunca, la sección de las últimas no aparece.
        assertTrue(
            compose.onAllNodesWithText(text(R.string.home_recent_sales))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun aDayWithoutSalesWaitsForTheFirstOne() {
        show(previewHomeContent().copy(hero = TodayHero.NoSalesYet))

        compose.onNodeWithText(text(R.string.empty_home_day_body)).assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText(text(R.string.term_sales_profit))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun theDiscreetModeHidesTheMoneyButNotTheNumberOfSales() {
        show(previewHomeContent(), discreet = true)

        // Ventas de hoy, ganancia y margen: tres cifras ocultas.
        assertEquals(
            3,
            compose.onAllNodesWithContentDescription(text(R.string.a11y_discreet_hidden))
                .fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.stats_sales_count, 12, 12),
        ).assertIsDisplayed()
    }

    @Test
    fun aReadErrorCanBeRetried() {
        show(HomeUiState.ReadError)

        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<HomeAction>(HomeAction.Retry), actions)
    }

    @Test
    fun theWeekShowsItsChangeAndTheMonthWithoutOneKeepsTheCaption() {
        compose.setContent {
            PreviewTheme {
                HomeScreen(previewHomeContent(), { actions += it }, profit = previewHomeProfit())
            }
        }

        // La semana sube 12,5 % frente a los mismos días de la anterior; el mes no tiene con qué
        // compararse y sigue diciendo que es después de gastos.
        compose.onNodeWithText("+12,5", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.home_after_expenses)).assertExists()
    }

    @Test
    fun theWeekAndMonthShowTheirFinalProfitAndTheExpenseShortcut() {
        compose.setContent {
            PreviewTheme {
                HomeScreen(previewHomeContent(), {
                    actions += it
                }, profit = previewHomeProfit().copy(weekChange = null))
            }
        }

        compose.onNodeWithText(text(R.string.home_this_week)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_this_month)).assertIsDisplayed()
        assertEquals(
            2,
            compose.onAllNodesWithText(text(R.string.home_after_expenses))
                .fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(text(R.string.home_shortcut_expense)).performClick()

        assertEquals(listOf<HomeAction>(HomeAction.OpenExpense), actions)
    }

    @Test
    fun withoutADestinationTheNoticeCanBeAcceptedAndShrinksToALine() {
        val noDestination =
            previewBackupState(previewOverview(BackupStatus.NOT_CONNECTED, kind = null)).wording
        var notice by mutableStateOf<NoDestinationNotice?>(NoDestinationNotice.FULL)
        compose.setContent {
            PreviewTheme {
                HomeScreen(
                    previewHomeContent(),
                    { actions += it },
                    backup = noDestination,
                    noDestinationNotice = notice,
                )
            }
        }
        val detail = text(R.string.backup_state_not_connected_detail)
        compose.onNodeWithText(detail).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_action_understood)).performClick()
        compose.onNodeWithText(text(R.string.backup_action_choose_destination)).performClick()
        assertEquals(
            listOf(HomeAction.AcknowledgeNoDestination, HomeAction.ChooseDestination),
            actions,
        )

        // Reducido: una línea que también lleva a elegir dónde guardar, sin la frase entera.
        notice = NoDestinationNotice.COMPACT
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(detail).fetchSemanticsNodes().size)
        compose.onNodeWithText(text(R.string.backup_not_connected_compact)).performClick()
        assertEquals(HomeAction.ChooseDestination, actions.last())

        // Mientras se lee si se aceptó, no va ninguno de los dos.
        notice = null
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(detail).fetchSemanticsNodes().size)
        val line = text(R.string.backup_not_connected_compact)
        assertEquals(0, compose.onAllNodesWithText(line).fetchSemanticsNodes().size)
        // El chip sigue diciendo que no hay respaldo: su texto vive dentro de su lectura única.
        compose.onNodeWithText(text(R.string.backup_state_not_connected), useUnmergedTree = true)
            .assertExists()
    }
}
