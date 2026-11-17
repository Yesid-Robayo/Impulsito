package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhrasePrices
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseReadResult
import io.github.yesidrobayo.shopledger.format.IcuTextNormalizer
import io.github.yesidrobayo.shopledger.ui.clickWhenStill
import io.github.yesidrobayo.shopledger.ui.currency.MoneyDisplay
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Decir o escribir` en el teléfono, con catálogos y frases inventados: cada estado de la hoja, y
 * el camino completo de escribir una frase, ver la tarjeta, elegir entre dos productos parecidos y
 * registrar. Los casos de uso son falsos: la prueba ve la tarjeta que llega a registrarse.
 */
@RunWith(AndroidJUnit4::class)
class PhraseScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private fun showState(state: PhraseUiState) {
        compose.setContent { PreviewTheme { PhraseScreen(state, onAction = {}) } }
    }

    /** Cómo se escribe cada monto con los formatos de la prueba, y cómo lo lee TalkBack. */
    private val money = mutableMapOf<Long, MoneyDisplay>()

    /** Muestra la hoja de [model], y anota cómo se ven los montos de [amounts]. */
    private fun show(model: PhraseViewModel, amounts: List<Long> = emptyList()) {
        compose.setContent {
            PreviewTheme {
                val formatter = LocalAppFormats.current.money
                for (amount in amounts) {
                    money[amount] = rememberMoneyDisplay(Money(amount), formatter)
                }
                val state by model.uiState.collectAsState()
                PhraseScreen(state, model::onAction)
            }
        }
    }

    /** Si hay un monto en pantalla que TalkBack lee como [amount]. */
    private fun showsAmount(amount: Long): Boolean {
        val display = requireNotNull(money[amount]) { "Falta anotar el monto $amount" }
        return compose
            .onAllNodes(hasContentDescription(display.description), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }

    private fun card(model: PhraseViewModel): PhraseCard =
        requireNotNull((model.uiState.value.stage as? PhraseStage.Card)?.card) { "Sin tarjeta" }

    @Test
    fun eachLineShowsItsAmountAndTheTotalIsTheirSum() {
        show(viewModel(read = { pricedResult() }), amounts = listOf(5_000L, 1_200L, 6_200L))

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa pequeña") }

        // 2 × 2.500 y 1 × 1.200, con el precio de cada producto, y el total de las dos.
        compose.waitUntil(TIMEOUT_MS) { showsAmount(5_000L) }
        assertEquals(true, showsAmount(1_200L))
        assertEquals(true, showsAmount(6_200L))
        assertEquals(false, has(text(R.string.phrase_line_price_from_product)))
        assertEquals(false, has(text(R.string.phrase_total_at_register)))
    }

    @Test
    fun theReadPricesStayAfterACorrection() {
        val model =
            viewModel(
                read = { pricedResult() },
                correct = { draft, _, _ ->
                    val first = draft.lines.first().copy(quantity = QuantityMilli.ofUnits(3))
                    draft.copy(lines = listOf(first) + draft.lines.drop(1))
                },
            )
        show(model, amounts = listOf(7_500L, 8_700L))

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa pequeña") }
        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput("no, eran 3")
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()

        compose.waitUntil(TIMEOUT_MS) { showsAmount(7_500L) }
        assertEquals(true, showsAmount(8_700L))
        assertEquals(Money(8_700L), card(model).total)
    }

    @Test
    fun aCardClosesTheKeyboard() {
        show(viewModel(read = { pricedResult() }))

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa pequeña") }

        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.phrase_field_label)).assertIsNotFocused()
    }

    @Test
    fun onCreditIsOnePaymentMethodThatMakesTheSaleOnCredit() {
        val model = viewModel(read = { pricedResult() })
        show(model)

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has(ON_CREDIT_NAME) }

        // Un solo chip: el método que trae la app, que es lo que se registra.
        val chips = compose.onAllNodes(hasText(ON_CREDIT_NAME), useUnmergedTree = true)
        assertEquals(1, chips.fetchSemanticsNodes().size)
        compose.onNodeWithText(ON_CREDIT_NAME).performClick()

        compose.waitUntil(TIMEOUT_MS) { card(model).credit }
        assertEquals(ON_CREDIT, card(model).payment?.selectedId)

        // Otro método la vuelve de contado.
        compose.onNodeWithText("Efectivo").performClick()
        compose.waitUntil(TIMEOUT_MS) { !card(model).credit }
        assertEquals(CASH, card(model).payment?.selectedId)
    }

    @Test
    fun aPurchaseWithoutCostOffersTheLastOneWithoutUsingIt() {
        val model = viewModel(read = { purchaseResult() })
        show(model, amounts = listOf(1_800L, 3_600L))

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(PURCHASE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_cost_missing)) }

        // La sugerencia no se pone sola: la línea sigue sin costo hasta tocarla.
        assertEquals(null, card(model).lines.single().unitPrice)
        val shown = requireNotNull(money[1_800L]).text.text
        val suggestion = text(R.string.phrase_use_last_cost, shown)
        compose.onNodeWithText(suggestion).performClick()

        compose.waitUntil(TIMEOUT_MS) { showsAmount(3_600L) }
        assertEquals(Money(1_800L), card(model).lines.single().unitPrice)
        assertEquals(false, has(text(R.string.phrase_cost_missing)))
    }

    @Test
    fun theEmptyStateShowsTheFieldWithExamples() {
        showState(PhraseUiState())

        compose.onNodeWithText(text(R.string.phrase_field_label)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_field_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_example_sale)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_understand)).assertIsDisplayed()
    }

    @Test
    fun understandWithoutTextAsksForAPhrase() {
        val reads = mutableListOf<String>()
        show(
            viewModel(read = {
                reads += it
                saleResult()
            }),
        )

        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()

        compose.onNodeWithText(text(R.string.phrase_field_empty)).assertIsDisplayed()
        assertEquals(emptyList<String>(), reads)
    }

    @Test
    fun whileReadingTheExamplesGiveWayAndTheTextStays() {
        val pending = CompletableDeferred<PhraseReadResult>()
        show(viewModel(read = { pending.await() }))

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()

        compose.waitUntil(TIMEOUT_MS) { !has(text(R.string.phrase_examples_title)) }
        compose.onNodeWithText(SALE_PHRASE).assertIsDisplayed()
        pending.complete(saleResult())
    }

    @Test
    fun aPhraseThatSaysNothingAsksToSayItAnotherWay() {
        showState(PhraseUiState(text = "eso de ayer", stage = PhraseStage.NotUnderstood))

        compose.onNodeWithText(text(R.string.phrase_not_understood)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_example_purchase)).assertIsDisplayed()
        // Lo escrito sigue en el campo, para corregirlo.
        compose.onNodeWithText("eso de ayer").assertIsDisplayed()
    }

    @Test
    fun twoOperationsInOnePhraseAskForOneAtATime() {
        show(viewModel(read = { PhraseReadResult(mixedReading(), catalog(), draft = null) }))

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(MIXED_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()

        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_mixed)) }
        compose.onNodeWithText(text(R.string.phrase_mixed)).assertIsDisplayed()
    }

    @Test
    fun aFailedReadKeepsTheTextAndOffersRetry() {
        showState(PhraseUiState(text = SALE_PHRASE, stage = PhraseStage.ReadFailed))

        compose.onNodeWithText(text(R.string.state_error_read)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).assertIsDisplayed()
        compose.onNodeWithText(SALE_PHRASE).assertIsDisplayed()
    }

    @Test
    fun theCardShowsItsLinesPaymentAndRegister() {
        showState(PhraseUiState(stage = PhraseStage.Card(PhraseSamples.sale)))

        compose.onNodeWithText(text(R.string.phrase_intent_sale)).assertIsDisplayed()
        compose.onNodeWithText("Pan").assertIsDisplayed()
        compose.onNodeWithText("Gaseosa").assertIsDisplayed()
        compose.onNodeWithText("Efectivo").assertIsDisplayed()
        // Una línea cuyo monto no se puede saber dice de dónde saldrá, sin inventarlo.
        compose.onNodeWithText(text(R.string.phrase_line_price_from_product)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_register)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_field_hint_correct)).assertIsDisplayed()
    }

    @Test
    fun anIncompleteCardMarksWhatIsMissingInItsPlace() {
        showState(
            PhraseUiState(
                stage =
                    PhraseStage.Card(
                        PhraseSamples.purchaseToChoose,
                        failure = PhraseFailure.Incomplete,
                    ),
            ),
        )

        compose.onNodeWithText(text(R.string.phrase_incomplete)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_line_no_product)).assertIsDisplayed()
        compose.onNodeWithText("Harina de trigo").assertIsDisplayed()
        compose.onNodeWithText("Harina de maíz").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_cost_missing)).assertIsDisplayed()
    }

    @Test
    fun aFailedSaveKeepsTheCard() {
        showState(
            PhraseUiState(
                stage =
                    PhraseStage.Card(
                        PhraseSamples.sale,
                        failure = PhraseFailure.SaveFailed(outOfSpace = false),
                    ),
            ),
        )

        compose.onNodeWithText(text(R.string.state_error_save)).assertIsDisplayed()
        compose.onNodeWithText("Pan").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_register)).assertIsDisplayed()
    }

    @Test
    fun aLongNameAndAmountStayWhole() {
        showState(PhraseUiState(stage = PhraseStage.Card(PhraseSamples.long)))

        compose.onNodeWithText(PhraseSamples.LONG_NAME).assertIsDisplayed()
    }

    @Test
    fun aPhraseBecomesACardAndAChosenCandidateIsWhatRegisters() {
        val registered = mutableListOf<PhraseDraft>()
        val model =
            viewModel(
                read = { saleResult() },
                register = {
                    registered += it
                    PhraseOutcome.Registered(PhraseIntent.SALE, documentId = 7L)
                },
            )
        show(model)

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa pequeña") }

        // Hay dos parecidas: la tarjeta pregunta, y registrar sin elegir dice qué falta.
        compose.onNodeWithText(text(R.string.phrase_register)).performClick()
        compose.onNodeWithText(text(R.string.phrase_incomplete)).assertIsDisplayed()
        assertEquals(emptyList<PhraseDraft>(), registered)

        compose.onNodeWithText("Gaseosa pequeña").performClick()
        compose.onNodeWithText(text(R.string.phrase_register)).performClick()
        compose.waitUntil(TIMEOUT_MS) { registered.isNotEmpty() }

        val draft = registered.single()
        assertEquals(DraftTarget.Existing(SMALL_SODA), draft.lines.single().product)
        assertEquals(QuantityMilli.ofUnits(2), draft.lines.single().quantity)
        assertEquals(CASH, draft.paymentMethodId)
        // Registrada, la hoja queda lista para otra frase, y la ruta muestra el mensaje.
        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_examples_title)) }
        assertEquals(PhraseRegistered(PhraseIntent.SALE, 7L), model.uiState.value.registered)
    }

    @Test
    fun aShortPhraseCorrectsTheCardWithoutReadingAgain() {
        val reads = mutableListOf<String>()
        val model =
            viewModel(
                read = {
                    reads += it
                    chosenResult()
                },
                correct = { draft, said, _ ->
                    if (said.startsWith("no")) {
                        val three = QuantityMilli.ofUnits(3)
                        draft.copy(lines = draft.lines.map { it.copy(quantity = three) })
                    } else {
                        null
                    }
                },
            )
        show(model)

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa grande") }
        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput("no, eran 3")
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()

        val three = "3" + IcuTextNormalizer.NO_BREAK_SPACE + text(R.string.unit_symbol_unit)
        compose.waitUntil(TIMEOUT_MS) { has(three) }
        assertEquals(listOf(SALE_PHRASE), reads)
    }

    @Test
    fun aPhraseThatIsNotACorrectionIsReadAsANewOne() {
        val reads = mutableListOf<String>()
        show(
            viewModel(read = {
                reads += it
                chosenResult()
            }, correct = { _, _, _ -> null }),
        )

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa grande") }
        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(MIXED_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()

        compose.waitUntil(TIMEOUT_MS) { reads.size == 2 }
        assertEquals(listOf(SALE_PHRASE, MIXED_PHRASE), reads)
    }

    @Test
    fun aLineIsCorrectedInItsSheet() {
        val registered = mutableListOf<PhraseDraft>()
        val model =
            viewModel(
                read = { chosenResult() },
                register = {
                    registered += it
                    PhraseOutcome.Registered(PhraseIntent.SALE, documentId = 8L)
                },
            )
        show(model)

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa grande") }
        compose.onNodeWithText("Gaseosa grande").performClick()
        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_quantity)) }
        compose.onNodeWithText(text(R.string.phrase_quantity)).performTextReplacement("4")
        compose.clickWhenStill(compose.onNodeWithText(text(R.string.phrase_done)))
        compose.waitUntil(TIMEOUT_MS) { !has(text(R.string.phrase_done)) }
        compose.onNodeWithText(text(R.string.phrase_register)).performClick()
        compose.waitUntil(TIMEOUT_MS) { registered.isNotEmpty() }

        assertEquals(QuantityMilli.ofUnits(4), registered.single().lines.single().quantity)
    }

    @Test
    fun aRejectedRegistrationSaysSoAndKeepsTheCard() {
        show(viewModel(read = { chosenResult() }, register = { PhraseOutcome.Rejected }))

        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(SALE_PHRASE)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitUntil(TIMEOUT_MS) { has("Gaseosa grande") }
        compose.onNodeWithText(text(R.string.phrase_register)).performClick()

        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_rejected)) }
        compose.onNodeWithText("Gaseosa grande").assertIsDisplayed()
    }

    /** Si algo en pantalla dice exactamente [value]. */
    private fun has(value: String): Boolean = compose
        .onAllNodes(hasText(value), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun viewModel(
        read: suspend (String) -> PhraseReadResult,
        correct: (PhraseDraft, String, PhraseCatalog) -> PhraseDraft? = { _, _, _ -> null },
        register: suspend (PhraseDraft) -> PhraseOutcome = {
            PhraseOutcome.Registered(it.intent, documentId = 1L)
        },
    ): PhraseViewModel = PhraseViewModel(
        read = read,
        correct = correct,
        register = register,
        savedState = SavedStateHandle(),
        numbers = NUMBERS,
        computation = Dispatchers.Default,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SALE_PHRASE = "vendí 2 gaseosas en efectivo"
        const val MIXED_PHRASE = "vendí 2 panes y compré harina"
        const val BIG_SODA = 20L
        const val SMALL_SODA = 21L
        const val CASH = 1L
        const val ON_CREDIT = 2L
        const val ON_CREDIT_NAME = "A crédito"
        const val PURCHASE_PHRASE = "compré 2 gaseosas grandes"
        const val UNIT = 1L

        /** Los números de las pruebas: punto decimal, coma de miles y moneda sin decimales. */
        val NUMBERS =
            PhraseInputFormat(
                moneySymbols = symbols(),
                currencyDecimals = 0,
                quantitySymbols = symbols(),
            )

        fun symbols() = NumberSymbols(
            decimalSeparator = '.',
            minusSign = '-',
            grouping = DigitGrouping(',', 3, 3),
        )

        fun product(id: Long, name: String) = PhraseProduct(
            id = id,
            name = name,
            baseUnitId = UNIT,
            conversions = emptyList(),
            forSale = true,
            forPurchase = true,
            hasRecipe = false,
            stockMode = ProductStockMode.TRACKED,
        )

        fun catalog() = PhraseCatalog(
            products =
                listOf(
                    product(BIG_SODA, "Gaseosa grande"),
                    product(SMALL_SODA, "Gaseosa pequeña"),
                ),
            units =
                listOf(
                    PhraseUnit(
                        id = UNIT,
                        systemKey = "UNIT",
                        name = "Unidad",
                        symbol = null,
                        kind = UnitKind.COUNT,
                        baseAmountMilli = null,
                    ),
                ),
            paymentMethods =
                listOf(
                    PhraseName(CASH, null, "Efectivo"),
                    // El método inicial `A crédito`, con su clave como la lee el intérprete.
                    PhraseName(ON_CREDIT, "on_account", ON_CREDIT_NAME),
                ),
            suppliers = emptyList(),
            customers = emptyList(),
            expenseCategories = emptyList(),
        )

        /** "Gaseosas" sin decir cuál: dos igual de posibles. */
        fun saleResult(): PhraseReadResult {
            val reading =
                PhraseReading(
                    intent = PhraseIntent.SALE,
                    lines =
                        listOf(
                            PhraseLine(
                                product = ProductMatch.Choose(listOf(BIG_SODA, SMALL_SODA)),
                                quantity = QuantityMilli.ofUnits(2),
                                unitId = null,
                                unitPrice = null,
                                lineTotal = null,
                            ),
                        ),
                    payment = NameMatch.Found(CASH),
                )
            val draft =
                PhraseDraft(
                    intent = PhraseIntent.SALE,
                    lines =
                        listOf(
                            DraftLine(
                                product = null,
                                quantity = QuantityMilli.ofUnits(2),
                                unitId = null,
                            ),
                        ),
                    paymentMethodId = CASH,
                )
            return PhraseReadResult(reading, catalog(), draft)
        }

        /** Una venta ya clara: dos gaseosas grandes en efectivo. */
        fun chosenResult(): PhraseReadResult {
            val reading =
                PhraseReading(
                    intent = PhraseIntent.SALE,
                    lines =
                        listOf(
                            PhraseLine(
                                product = ProductMatch.Found(BIG_SODA),
                                quantity = QuantityMilli.ofUnits(2),
                                unitId = null,
                                unitPrice = null,
                                lineTotal = null,
                            ),
                        ),
                    payment = NameMatch.Found(CASH),
                )
            val draft =
                PhraseDraft(
                    intent = PhraseIntent.SALE,
                    lines =
                        listOf(
                            DraftLine(
                                product = DraftTarget.Existing(BIG_SODA),
                                quantity = QuantityMilli.ofUnits(2),
                                unitId = null,
                            ),
                        ),
                    paymentMethodId = CASH,
                )
            return PhraseReadResult(reading, catalog(), draft)
        }

        /** Precios de la lista inicial y el último costo de la gaseosa grande. */
        val PRICES =
            PhrasePrices(
                sale = mapOf(BIG_SODA to Money(2_500L), SMALL_SODA to Money(1_200L)),
                lastCost = mapOf(BIG_SODA to Money(1_800L)),
            )

        /** Dos gaseosas grandes y una pequeña en efectivo, sin precios dichos. */
        fun pricedResult(): PhraseReadResult {
            val lines =
                listOf(BIG_SODA to 2L, SMALL_SODA to 1L).map { (id, units) ->
                    PhraseLine(
                        product = ProductMatch.Found(id),
                        quantity = QuantityMilli.ofUnits(units),
                        unitId = null,
                        unitPrice = null,
                        lineTotal = null,
                    )
                }
            val reading =
                PhraseReading(
                    intent = PhraseIntent.SALE,
                    lines = lines,
                    payment = NameMatch.Found(CASH),
                )
            val draft =
                PhraseDraft(
                    intent = PhraseIntent.SALE,
                    lines =
                        listOf(BIG_SODA to 2L, SMALL_SODA to 1L).map { (id, units) ->
                            DraftLine(
                                product = DraftTarget.Existing(id),
                                quantity = QuantityMilli.ofUnits(units),
                                unitId = null,
                            )
                        },
                    paymentMethodId = CASH,
                )
            return PhraseReadResult(reading, catalog(), draft, PRICES)
        }

        /** Dos gaseosas grandes compradas, sin decir el costo. */
        fun purchaseResult(): PhraseReadResult {
            val reading =
                PhraseReading(
                    intent = PhraseIntent.PURCHASE,
                    lines =
                        listOf(
                            PhraseLine(
                                product = ProductMatch.Found(BIG_SODA),
                                quantity = QuantityMilli.ofUnits(2),
                                unitId = null,
                                unitPrice = null,
                                lineTotal = null,
                            ),
                        ),
                )
            val draft =
                PhraseDraft(
                    intent = PhraseIntent.PURCHASE,
                    lines =
                        listOf(
                            DraftLine(
                                product = DraftTarget.Existing(BIG_SODA),
                                quantity = QuantityMilli.ofUnits(2),
                                unitId = null,
                            ),
                        ),
                )
            return PhraseReadResult(reading, catalog(), draft, PRICES)
        }

        fun mixedReading() =
            PhraseReading(intent = null, issues = setOf(PhraseIssue.MIXED_OPERATIONS))
    }
}
