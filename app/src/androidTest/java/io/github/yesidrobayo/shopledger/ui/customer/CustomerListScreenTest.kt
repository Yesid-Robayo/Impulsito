package io.github.yesidrobayo.shopledger.ui.customer

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.clickWhenStill
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lista de clientes en pantalla: cada estado, los saldos dichos con palabras, el modo discreto que
 * deja la palabra sin el monto, y la hoja con solo el nombre obligatorio. Con el argumento
 * `captureDir` también deja las capturas de la puerta de calidad, en claro y oscuro.
 */
@RunWith(AndroidJUnit4::class)
class CustomerListScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(context.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<CustomerListAction>()
    private val editorActions = mutableListOf<CustomerEditorAction>()

    @Test
    fun theListSaysEachBalanceWithWordsAndTheReceivable() {
        show(LIST)

        // La tarjeta y el filtro dicen lo mismo.
        compose.onAllNodesWithText(text(R.string.customer_receivable)).assertCountEquals(2)
        compose.onNodeWithText("Ana Ríos").assertIsDisplayed()
        compose.onNode(balance(R.string.customer_balance_owes, "4.000"), useUnmergedTree = true)
            .assertExists()
        compose.onNode(balance(R.string.customer_balance_credit, "1.500"), useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText(text(R.string.customer_balance_clear), useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText(text(R.string.customer_anonymous), useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText("Ana Ríos").performClick()

        assertEquals(listOf(CustomerListAction.OpenCustomer(1L)), actions)
    }

    @Test
    fun discreetModeKeepsTheWordAndHidesTheAmount() {
        show(LIST, discreet = true)

        compose.onAllNodesWithText(
            text(R.string.customer_balance_owes_short),
            useUnmergedTree = true,
        )
            .fetchSemanticsNodes().let { assertEquals(4, it.size) }
        compose.onNodeWithText(text(R.string.customer_balance_credit_short), useUnmergedTree = true)
            .assertExists()
        compose.onNode(balance(R.string.customer_balance_owes, "4.000"), useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(
            text(R.string.a11y_discreet_hidden),
            useUnmergedTree = true,
        )
            .assertExists()
    }

    @Test
    fun withPointsOnTheRowCountsThemWithTheRegionalFormat() {
        val many = CustomerSamples.customer(7, "Eva Luna", points = 12_500)
        show(LIST.copy(content = CustomerListContent.Customers(listOf(many)), showPoints = true))

        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.customer_points_count, 12_500, "12.500"),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun anEmptyBusinessInvitesToCreateTheFirstCustomer() {
        show(CustomerListUiState(content = CustomerListContent.Empty))

        compose.onNodeWithText(text(R.string.customer_empty_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.customer_new_action)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.customer_empty_action)).performClick()

        assertEquals(listOf(CustomerListAction.NewCustomer()), actions)
    }

    @Test
    fun noResultsOffersToCreateWithTheSearchedName() {
        show(LIST.copy(query = "Eva", content = CustomerListContent.NoResults("Eva")))

        compose.onNodeWithText(text(R.string.customer_search_no_results, "Eva")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.customer_create_action)).performClick()

        assertEquals(listOf(CustomerListAction.NewCustomer("Eva")), actions)
    }

    @Test
    fun anEmptyFilterOffersToClearIt() {
        show(LIST.copy(filter = CustomerFilter.CREDIT, content = CustomerListContent.FilterEmpty))

        compose.onNodeWithText(text(R.string.customer_filter_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_clear_filters)).performClick()

        assertEquals(listOf(CustomerListAction.ClearFilters), actions)
    }

    @Test
    fun anArchivedCustomerCanBeReactivated() {
        show(
            LIST.copy(
                filter = CustomerFilter.ARCHIVED,
                content = CustomerListContent.Customers(listOf(CustomerSamples.list[4])),
            ),
        )

        // La etiqueta de la fila y el filtro elegido, cada uno con su texto: en español el filtro
        // dice `Archivados` y la etiqueta `Archivado`.
        compose.onAllNodesWithText(text(R.string.customer_archived_tag), useUnmergedTree = true)
            .assertCountEquals(if (archivedSameAsFilter()) 2 else 1)
        compose.onAllNodesWithText(text(R.string.customer_filter_archived), useUnmergedTree = true)
            .assertCountEquals(if (archivedSameAsFilter()) 2 else 1)
        compose.onNodeWithText(text(R.string.action_reactivate), useUnmergedTree = true)
            .performClick()

        assertEquals(listOf(CustomerListAction.Reactivate(5L)), actions)
    }

    @Test
    fun theSheetAsksOnlyForTheNameAndOffersTheContacts() {
        showSheet(CustomerEditor(editingId = null, name = ""))

        compose.onNodeWithText(text(R.string.customer_minimal_data_help)).assertExists()
        compose.clickWhenStill(
            compose.onNodeWithText(text(R.string.customer_pick_contact_action)),
        )
        compose.clickWhenStill(compose.onNodeWithText(text(R.string.customer_save_action)))

        assertEquals(
            listOf(CustomerEditorAction.PickContact, CustomerEditorAction.Save),
            editorActions,
        )
    }

    @Test
    fun theSheetWarnsOfARepeatedNameWithoutBlocking() {
        showSheet(CustomerEditor(editingId = null, name = "Ana Ríos", sameNameWarned = true))

        compose.onNodeWithText(text(R.string.customer_duplicate_warning)).assertExists()
        compose.clickWhenStill(compose.onNodeWithText(text(R.string.customer_save_action)))

        assertEquals(listOf(CustomerEditorAction.Save), editorActions)
    }

    @Test
    fun capturesEveryStateInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states =
            listOf(
                "list" to LIST,
                "points" to LIST.copy(showPoints = true),
                "empty" to CustomerListUiState(content = CustomerListContent.Empty),
                "no-results" to
                    LIST.copy(query = "Eva", content = CustomerListContent.NoResults("Eva")),
                "filter-empty" to
                    LIST.copy(
                        filter = CustomerFilter.CREDIT,
                        content = CustomerListContent.FilterEmpty,
                    ),
                "read-failed" to CustomerListUiState(content = CustomerListContent.ReadError),
            )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for ((name, state) in states) {
                show(state, dark = dark)
                shoot(requireNotNull(dir), "$name-$mode")
            }
            show(LIST, dark = dark, discreet = true)
            shoot(requireNotNull(dir), "discreet-$mode")
            showSheet(SHEET_ERRORS, dark = dark)
            shoot(requireNotNull(dir), "sheet-$mode")
        }
    }

    private fun show(state: CustomerListUiState, dark: Boolean = false, discreet: Boolean = false) {
        setScreen(dark, discreet) { CustomerListScreen(state, { actions += it }) }
    }

    private fun showSheet(editor: CustomerEditor, dark: Boolean = false) {
        setScreen(dark, discreet = false) {
            CustomerListScreen(LIST, {})
            CustomerEditorSheet(editor, { editorActions += it }, asPanel = false)
        }
    }

    private fun setScreen(
        dark: Boolean,
        discreet: Boolean,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(dir: File, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * La insignia de un saldo: TalkBack lee la frase de [id] con el monto, que lleva [digits] en el
     * formato del negocio.
     */
    private fun balance(id: Int, digits: String): SemanticsMatcher {
        val word = text(id, "").trim()
        return SemanticsMatcher("saldo $word $digits") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                it.contains(word) && it.contains(digits)
            }
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 700L

        val LIST = CustomerListUiState(
            content = CustomerListContent.Customers(CustomerSamples.list),
            receivable = Money(128_400),
        )

        val SHEET_ERRORS = CustomerEditor(
            editingId = null,
            name = CustomerSamples.LONG_NAME,
            phone = "555-CALL",
            issues = setOf(CustomerFormIssue.PHONE_FORMAT),
            sameNameWarned = true,
            saveFailed = SaveFailure(outOfSpace = false),
        )
    }

    /** Si el filtro y la etiqueta dicen lo mismo en el idioma de la prueba, como en inglés. */
    private fun archivedSameAsFilter(): Boolean =
        text(R.string.customer_archived_tag) == text(R.string.customer_filter_archived)
}
