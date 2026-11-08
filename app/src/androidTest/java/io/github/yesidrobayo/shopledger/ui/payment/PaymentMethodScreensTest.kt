package io.github.yesidrobayo.shopledger.ui.payment

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodOptions
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lista y el formulario de métodos de pago muestran cada estado con su estado falso: con datos,
 * archivados plegados, sin vigentes, error de lectura, el modo de ordenar con sus acciones para
 * TalkBack, el tipo bloqueado y el cobro a crédito sin comisión. Los datos son los de las vistas
 * previas, inventados.
 */
@RunWith(AndroidJUnit4::class)
class PaymentMethodScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<Any>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private fun list(state: PaymentMethodsUiState) {
        compose.setContent { PreviewTheme { PaymentMethodsScreen(state, { actions += it }) } }
    }

    private fun form(state: PaymentMethodFormUiState) {
        compose.setContent { PreviewTheme { PaymentMethodFormScreen(state, { actions += it }) } }
    }

    @Test
    fun theListShowsTheInitialNamesAndFoldsTheArchived() {
        list(PaymentMethodsUiState.Ready(SAMPLE_ACTIVE, SAMPLE_ARCHIVED))

        compose.onNodeWithText(text(R.string.seed_payment_methods_cash)).assertIsDisplayed()
        val archived = text(R.string.seed_payment_methods_wallet)
        assertEquals(0, compose.onAllNodesWithText(archived).fetchSemanticsNodes().size)

        compose.onNodeWithText(
            text(R.string.payment_method_group_archived, 1),
        ).performScrollTo().performClick()

        assertEquals(listOf<Any>(PaymentMethodsAction.ToggleArchived), actions)
    }

    @Test
    fun anExpandedArchiveShowsItsMethodsAndOpensThem() {
        list(PaymentMethodsUiState.Ready(SAMPLE_ACTIVE, SAMPLE_ARCHIVED, archivedExpanded = true))

        compose.onNodeWithText(
            text(R.string.seed_payment_methods_wallet),
        ).performScrollTo().performClick()

        assertEquals(listOf<Any>(PaymentMethodsAction.OpenMethod(4)), actions)
    }

    @Test
    fun withoutActiveMethodsTheListSaysSo() {
        list(PaymentMethodsUiState.Ready(emptyList(), SAMPLE_ARCHIVED))

        compose.onNodeWithText(text(R.string.payment_method_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.payment_method_empty_body)).assertIsDisplayed()
    }

    @Test
    fun aReadErrorOffersToTryAgain() {
        list(PaymentMethodsUiState.ReadError)

        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<Any>(PaymentMethodsAction.Retry), actions)
    }

    @Test
    fun sortingMovesWithButtonsAndWithTalkBackActions() {
        list(
            PaymentMethodsUiState.Ready(
                SAMPLE_ACTIVE,
                emptyList(),
                sorting = SortingState(SAMPLE_ACTIVE),
            ),
        )
        val down = text(R.string.payment_method_move_down)

        compose.onAllNodesWithContentDescription(down)[0].performClick()
        val cash = compose.onNodeWithText(
            text(R.string.seed_payment_methods_cash),
            useUnmergedTree = false,
        )
        customAction(cash, down)
        compose.onNodeWithText(text(R.string.payment_method_sort_save_action)).performClick()

        assertEquals(
            listOf(
                PaymentMethodsAction.Move(0, 1),
                PaymentMethodsAction.Move(0, 1),
                PaymentMethodsAction.SaveOrder,
            ),
            actions,
        )
    }

    @Test
    fun aMethodWithSalesShowsOnlyItsKindAndItsOptions() {
        val card = SAMPLE_ACTIVE[1]
        form(
            PaymentMethodFormUiState.Editing(
                methodId = card.id,
                systemKey = card.systemKey,
                form = PaymentMethodForm(kind = PaymentMethodKind.CARD),
                options = PaymentMethodOptions(card, hasSalePayments = true, activeCount = 6),
            ),
        )

        // El nombre inicial se ve traducido, y el tipo no se puede cambiar.
        // En inglés el nombre y el chip del tipo dicen lo mismo: se busca el campo editable.
        val cardName = text(R.string.seed_payment_methods_card)
        compose.onNode(hasText(cardName) and hasSetTextAction()).assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText(
                text(R.string.payment_method_kind_cash),
            ).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(text(R.string.payment_method_error_kind_locked)).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.payment_method_save_action)).performClick()
        assertEquals(listOf<Any>(PaymentMethodFormAction.Save), actions)
    }

    @Test
    fun onCreditHidesTheFeeAndSaysItNeedsACustomer() {
        form(
            PaymentMethodFormUiState.Editing(
                methodId = null,
                systemKey = null,
                form = PaymentMethodForm(nameEdited = true, kind = PaymentMethodKind.ON_ACCOUNT),
                options = null,
            ),
        )

        assertEquals(
            0,
            compose.onAllNodesWithText(
                text(R.string.payment_method_fee_percent),
            ).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(
            text(R.string.payment_method_on_account_requires),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.payment_method_new_action)).assertIsDisplayed()
    }

    @Test
    fun aFieldErrorAndAFullDiskAreShownWithoutLosingTheForm() {
        form(
            PaymentMethodFormUiState.Editing(
                methodId = null,
                systemKey = null,
                form = PaymentMethodForm(
                    name = "Vale",
                    nameEdited = true,
                    kind = PaymentMethodKind.OTHER,
                    feeRate = "101",
                ),
                options = null,
                errors = mapOf(FormField.FEE_RATE to FieldProblem.FEE_RATE_RANGE),
                notice = FormNotice.SaveFailed(SaveProblem.OUT_OF_SPACE),
            ),
        )

        compose.onNodeWithText(
            text(R.string.payment_method_error_fee_range),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.error_device_storage)).assertIsDisplayed()
        compose.onNodeWithText("Vale").assertIsDisplayed()
    }

    private fun customAction(node: SemanticsNodeInteraction, label: String) {
        val config = node.fetchSemanticsNode().config
        val action = config[SemanticsActions.CustomActions].firstOrNull { it.label == label }
        assertTrue("Falta la acción $label", action != null)
        compose.runOnUiThread { action?.action?.invoke() }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(
    text: String,
) = onAllNodes(androidx.compose.ui.test.hasText(text))
