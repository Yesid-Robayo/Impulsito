package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.product.usageLabel
import io.github.yesidrobayo.shopledger.ui.stock.reasonLabel
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Decir o escribir`: el campo de la frase arriba, con el teclado abierto, y debajo lo que se
 * entendió, en una tarjeta para confirmar. La acción principal va abajo, al alcance del pulgar:
 * `Entender` sin tarjeta y `Registrar` con ella.
 *
 * La tarjeta muestra la operación con números grandes, para revisarla de un vistazo antes de
 * registrar. Lo que falta se marca en su lugar con una etiqueta, nunca solo con color, y se
 * resuelve ahí mismo: un chip para elegir entre parecidos, `Crear «…»` o tocar la línea para
 * escribir la cantidad. Los costos van en modo discreto; el precio de lo que se vende, nunca.
 */
@Composable
fun PhraseScreen(
    uiState: PhraseUiState,
    onAction: (PhraseAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val stage = uiState.stage
    val card = stage as? PhraseStage.Card
    val scroll = rememberScrollState()
    var stageTop by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(uiState.cardShown) {
        if (uiState.cardShown == 0) return@LaunchedEffect
        // Con la tarjeta a la vista, el teclado la taparía: se cierra y la vista baja hasta ella.
        focusManager.clearFocus()
        keyboard?.hide()
        withFrameNanos {}
        scroll.animateScrollTo(stageTop)
    }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.phrase_title),
                navigation = TopBarNavigation.CLOSE,
                onNavigate = { onAction(PhraseAction.Close) },
                navigationLabel = stringResource(R.string.a11y_close),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.REGISTER_BY_PHRASE) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxSize(),
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(spacing.s16),
                        verticalArrangement = Arrangement.spacedBy(spacing.s16),
                    ) {
                        PhraseField(uiState, onAction, withCard = card != null)
                        Column(
                            Modifier.onGloballyPositioned {
                                stageTop = it.positionInParent().y.toInt()
                            },
                            verticalArrangement = Arrangement.spacedBy(spacing.s16),
                        ) {
                            StageContent(stage, onAction)
                        }
                    }
                    Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
                        HorizontalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Box(Modifier.padding(spacing.s16)) {
                            if (card != null) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.phrase_register),
                                    onClick = { onAction(PhraseAction.Register) },
                                    variant = ActionVariant.PRIMARY,
                                    state =
                                        if (card.registering) {
                                            ActionState.WORKING
                                        } else {
                                            ActionState.IDLE
                                        },
                                )
                            } else {
                                PrimaryActionButton(
                                    text = stringResource(R.string.phrase_understand),
                                    onClick = { onAction(PhraseAction.Understand) },
                                    variant = ActionVariant.PRIMARY,
                                    state =
                                        if (stage == PhraseStage.Reading) {
                                            ActionState.WORKING
                                        } else {
                                            ActionState.IDLE
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    PhraseSheets(uiState, onAction)
}

/**
 * El campo de la frase. Sin tarjeta, toma el foco y abre el teclado, para dictar con su micrófono.
 * Con la tarjeta, sirve para corregirla con una frase corta, y `Entender` va debajo, tonal: la
 * acción principal pasa a ser `Registrar`.
 *
 * Mientras se escucha o se lee una frase oída, el teclado no se abre: taparía la pantalla de
 * escuchar o saltaría justo antes de que llegue la tarjeta. Si `Decir` no pudo escuchar, un aviso
 * corto bajo el campo dice por qué.
 */
@Composable
private fun PhraseField(
    uiState: PhraseUiState,
    onAction: (PhraseAction) -> Unit,
    withCard: Boolean,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val holdKeyboard = uiState.listening || uiState.stage == PhraseStage.Reading
    LaunchedEffect(withCard, holdKeyboard) {
        when {
            // Abierto, el teclado empujaría la pantalla de escuchar hacia arriba.
            uiState.listening -> keyboard?.hide()

            !withCard && !holdKeyboard -> {
                focus.requestFocus()
                keyboard?.show()
            }
        }
    }
    val listen = stringResource(R.string.phrase_listen)
    AppTextField(
        value = uiState.text,
        onValueChange = { onAction(PhraseAction.TextChange(it)) },
        label = stringResource(R.string.phrase_field_label),
        modifier = Modifier.focusRequester(focus),
        error = if (uiState.textMissing) stringResource(R.string.phrase_field_empty) else null,
        supportingText =
            stringResource(
                if (withCard) R.string.phrase_field_hint_correct else R.string.phrase_field_hint,
            ),
        imeAction = ImeAction.Done,
        singleLine = false,
        keyboardActions = KeyboardActions(onDone = { onAction(PhraseAction.Understand) }),
        trailingIcon =
            if (uiState.voiceReady) {
                { TopBarAction(R.drawable.ic_mic, listen) { onAction(PhraseAction.Listen) } }
            } else {
                null
            },
    )
    uiState.voiceNotice?.let { VoiceNoticeCard(it, onAction) }
    if (withCard) {
        PrimaryActionButton(
            text = stringResource(R.string.phrase_understand),
            onClick = { onAction(PhraseAction.Understand) },
            variant = ActionVariant.TONAL,
        )
    }
}

/**
 * Por qué la hoja quedó con el teclado. Sin permiso que Android deje volver a pedir, ofrece abrir
 * los ajustes del teléfono: solo ahí se da, y solo si la persona lo toca.
 */
@Composable
private fun VoiceNoticeCard(notice: VoiceNotice, onAction: (PhraseAction) -> Unit) {
    when (notice) {
        VoiceNotice.FAILED ->
            NoticeCard(
                text = stringResource(R.string.phrase_voice_failed),
                variant = NoticeVariant.WARNING,
            )

        VoiceNotice.SERVICE_NO_MIC ->
            NoticeCard(
                text = stringResource(R.string.phrase_voice_service_no_mic),
                variant = NoticeVariant.WARNING,
            )

        VoiceNotice.NO_PERMISSION ->
            NoticeCard(
                text = stringResource(R.string.phrase_mic_denied),
                variant = NoticeVariant.INFO,
            )

        VoiceNotice.PERMISSION_BLOCKED ->
            NoticeCard(
                text = stringResource(R.string.phrase_mic_blocked),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.phrase_mic_open_settings),
                onAction = { onAction(PhraseAction.OpenMicSettings) },
            )
    }
}

/** Lo que va bajo el campo según en qué va la lectura. */
@Composable
private fun StageContent(stage: PhraseStage, onAction: (PhraseAction) -> Unit) {
    when (stage) {
        PhraseStage.Empty -> Examples(onAction)

        // El botón trabaja; lo escrito sigue a la vista. Sin esqueleto: la lectura es local.
        PhraseStage.Reading -> Unit

        PhraseStage.NotUnderstood -> {
            NoticeCard(
                text = stringResource(R.string.phrase_not_understood),
                variant = NoticeVariant.WARNING,
            )
            Examples(onAction)
        }

        PhraseStage.Mixed -> {
            NoticeCard(
                text = stringResource(R.string.phrase_mixed),
                variant = NoticeVariant.WARNING,
            )
            Examples(onAction)
        }

        PhraseStage.ReadFailed ->
            NoticeCard(
                text = stringResource(R.string.state_error_read),
                variant = NoticeVariant.ERROR,
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(PhraseAction.Retry) },
            )

        is PhraseStage.Card -> {
            stage.failure?.let { failure ->
                NoticeCard(
                    text =
                        stringResource(
                            when (failure) {
                                PhraseFailure.Incomplete -> R.string.phrase_incomplete
                                PhraseFailure.NotUnderstood -> R.string.phrase_not_understood
                                PhraseFailure.Mixed -> R.string.phrase_mixed
                                PhraseFailure.Rejected -> R.string.phrase_rejected
                                is PhraseFailure.SaveFailed -> saveErrorText(failure.outOfSpace)
                            },
                        ),
                    variant =
                        when (failure) {
                            // La frase nueva no se entendió: la tarjeta sigue lista, solo avisa.
                            PhraseFailure.NotUnderstood,
                            PhraseFailure.Mixed,
                            -> NoticeVariant.WARNING

                            PhraseFailure.Incomplete,
                            PhraseFailure.Rejected,
                            is PhraseFailure.SaveFailed,
                            -> NoticeVariant.ERROR
                        },
                )
            }
            stage.notice?.let { CardNoticeView(it, onAction) }
            PhraseCardView(
                card = stage.card,
                onAction = onAction,
                highlightGaps = stage.failure == PhraseFailure.Incomplete,
            )
        }
    }
}

/**
 * Unos ejemplos de lo que se puede decir, en el idioma de la interfaz, y dos salidas: vender a mano y
 * los pasos para activar el micrófono si el teclado dice que la voz no está disponible.
 */
@Composable
private fun Examples(onAction: (PhraseAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            text = stringResource(R.string.phrase_examples_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (example in EXAMPLES) {
            Text(
                text = stringResource(example),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InlineTextButton(
            text = stringResource(R.string.phrase_sell_by_hand),
            onClick = { onAction(PhraseAction.SellByHand) },
        )
        InlineTextButton(
            text = stringResource(R.string.phrase_mic_help),
            onClick = { onAction(PhraseAction.MicHelp) },
        )
    }
}

private val EXAMPLES =
    listOf(
        R.string.phrase_example_sale,
        R.string.phrase_example_purchase,
        R.string.phrase_example_expense,
        R.string.phrase_example_stock,
    )

/** La tarjeta para confirmar: la operación, sus líneas, sus datos y el total, de arriba abajo. */
@Composable
internal fun PhraseCardView(
    card: PhraseCard,
    onAction: (PhraseAction) -> Unit,
    highlightGaps: Boolean = false,
) {
    val spacing = AppTheme.spacing
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
            CardHeader(card)
            if (card.operations.isNotEmpty()) OperationChips(card, onAction)
            card.notices.forEach { issue ->
                NoticeCard(
                    text = stringResource(noticeText(issue)),
                    variant = NoticeVariant.WARNING,
                )
            }
            if (DraftGap.NOTHING in card.gaps) {
                NoticeCard(
                    text = stringResource(R.string.phrase_nothing),
                    variant = if (highlightGaps) NoticeVariant.ERROR else NoticeVariant.INFO,
                )
            }
            card.target?.let { target -> TargetSection(card, target, onAction) }
            if (card.intent == PhraseIntent.RECIPE && card.lines.isNotEmpty()) {
                SectionLabel(stringResource(R.string.phrase_ingredients))
            }
            card.lines.forEach { line -> LineRow(card, line, onAction) }
            if (card.intent == PhraseIntent.NEW_PRODUCT) NewProductSection(card, onAction)
            card.category?.let { CategorySection(it, card.gaps, onAction) }
            card.payment?.let { PaymentSection(card, it, onAction) }
            card.party?.let { PartySection(card, it, onAction) }
            TotalSection(card, onAction)
            InlineTextButton(
                text = stringResource(R.string.phrase_start_over),
                onClick = { onAction(PhraseAction.StartOver) },
            )
        }
    }
}

@Composable
private fun CardHeader(card: PhraseCard) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            text = stringResource(intentLabel(card.intent, card.reason)),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        val reason = card.reason
        if (card.intent == PhraseIntent.STOCK_ADJUSTMENT && reason != null &&
            reason != StockAdjustmentReason.COUNT_CORRECTION
        ) {
            StatusTag(text = reasonLabel(reason), tone = StatusTone.INFO)
        }
    }
}

/**
 * Lo que la última frase dejó sobre la tarjeta sin cambiarla: otra operación, con la pregunta de si
 * empezar otra tarjeta, o un producto que ya existe, con el botón que lo abre.
 */
@Composable
private fun CardNoticeView(notice: CardNotice, onAction: (PhraseAction) -> Unit) {
    when (notice) {
        is CardNotice.OtherOperation ->
            NoticeCard(
                text =
                    stringResource(
                        R.string.phrase_other_operation,
                        stringResource(intentLabel(notice.intent, notice.reason)),
                    ),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.phrase_start_new_card),
                onAction = { onAction(PhraseAction.StartNewCard) },
                secondaryLabel = stringResource(R.string.phrase_keep_card),
                onSecondary = { onAction(PhraseAction.KeepCard) },
            )

        is CardNotice.KnownProduct ->
            NoticeCard(
                text = stringResource(R.string.phrase_known_product, notice.name),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.phrase_open_product),
                onAction = { onAction(PhraseAction.OpenProduct(notice.productId)) },
            )
    }
}

/**
 * La operación de la tarjeta, para cambiarla con un toque: lo dicho sin verbo queda como venta, y
 * aquí se pasa a compra, conteo o producción sin perder las líneas.
 */
@Composable
private fun OperationChips(card: PhraseCard, onAction: (PhraseAction) -> Unit) {
    val selected = CardOperation(card.intent, card.reason)
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        SectionLabel(stringResource(R.string.phrase_operation))
        CompactChipRow(
            options = card.operations,
            label = { stringResource(intentLabel(it.intent, it.reason)) },
            selected = setOf(selected),
            onToggle = { onAction(PhraseAction.ChooseOperation(it)) },
            multiple = false,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Una etiqueta de lo que falta: el texto lo dice, y el icono de aviso lo refuerza. */
@Composable
private fun GapTag(@StringRes text: Int) {
    StatusTag(text = stringResource(text), icon = R.drawable.ic_warning, tone = StatusTone.WARNING)
}

/**
 * Una línea: la cantidad grande a la izquierda, el nombre con el ancho que sobra y el monto a la
 * derecha. Debajo, a todo el ancho, lo que conviene saber de la línea: lo que falta, un producto
 * que no es de esta operación o el último costo para elegirlo. Toda la línea se toca para
 * corregirla o quitarla. Un producto por elegir muestra sus parecidos en chips, `Elegir otro` y, si
 * se puede, `Crear «…»`.
 */
@Composable
private fun LineRow(card: PhraseCard, line: CardLine, onAction: (PhraseAction) -> Unit) {
    val spacing = AppTheme.spacing
    val editLabel = stringResource(R.string.phrase_edit_line)
    val pricedOnly = card.intent == PhraseIntent.PRICE_CHANGE ||
        card.intent == PhraseIntent.COST_CORRECTION
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(onClickLabel = editLabel, role = Role.Button) {
                onAction(PhraseAction.OpenLine(line.index))
            }.padding(vertical = spacing.s4),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val quantity = line.quantity
            if (!pricedOnly && quantity != null) {
                Text(text = lineQuantity(quantity, line.unit), style = AppTheme.amounts.tile)
            }
            LineTitle(line.product, Modifier.weight(1f))
            LineAmount(card, line, pricedOnly)
        }
        LineNotes(card, line, pricedOnly, onAction)
        when (val product = line.product) {
            is CardProduct.Missing ->
                MissingProduct(
                    product = product,
                    onChoose = { onAction(PhraseAction.ChooseProduct(line.index, it)) },
                    onPick = { onAction(PhraseAction.PickProduct(line.index)) },
                    onCreate = { onAction(PhraseAction.CreateProduct(line.index, it)) },
                )

            is CardProduct.New ->
                if (product.needsCreating) {
                    CreateOrPick(
                        name = product.name,
                        onCreate = { onAction(PhraseAction.CreateProduct(line.index, it)) },
                        onPick = { onAction(PhraseAction.PickProduct(line.index)) },
                    )
                }

            is CardProduct.Chosen -> Unit
        }
    }
}

/** El nombre de la línea, o lo que se dijo si todavía falta elegir el producto. */
@Composable
private fun LineTitle(product: CardProduct, modifier: Modifier) {
    when (product) {
        is CardProduct.Chosen ->
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = modifier,
            )

        is CardProduct.New ->
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = modifier,
            )

        is CardProduct.Missing ->
            Text(
                text = product.spoken?.let { stringResource(R.string.phrase_heard, it) }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
    }
}

/**
 * Lo que conviene saber de la línea, a todo el ancho, bajo su fila: lo que falta, si el producto es
 * nuevo o de otra operación, la unidad de un precio y, en una compra sin costo, el último costo
 * para elegirlo con un toque.
 */
@Composable
private fun LineNotes(
    card: PhraseCard,
    line: CardLine,
    pricedOnly: Boolean,
    onAction: (PhraseAction) -> Unit,
) {
    when (val product = line.product) {
        is CardProduct.Missing -> GapTag(R.string.phrase_line_no_product)

        is CardProduct.New -> {
            if (product.needsCreating) {
                GapTag(R.string.phrase_line_create_first)
            } else {
                StatusTag(text = stringResource(R.string.phrase_new_tag), tone = StatusTone.INFO)
            }
            NewProductTraits(product) { onAction(PhraseAction.PickNewCategory(line.index)) }
        }

        is CardProduct.Chosen -> Unit
    }
    if (line.quantity == null && !pricedOnly) GapTag(R.string.phrase_line_no_quantity)
    if (line.otherOperation) {
        StatusTag(
            text = stringResource(otherOperationText(card.intent)),
            tone = StatusTone.WARNING,
        )
    }
    if (pricedOnly) {
        line.unit?.let { unit ->
            Text(
                text = stringResource(R.string.phrase_per_unit, unitText(unit)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val unpriced = line.amount == null && line.unitPrice == null
    when {
        // Una compra nunca supone un costo no dicho: lo pide, y ofrece el último para elegirlo.
        card.intent == PhraseIntent.PURCHASE && unpriced -> {
            GapTag(R.string.phrase_cost_missing)
            line.lastCost?.let { cost ->
                LastCostButton(cost) { onAction(PhraseAction.UseLastCost(line.index)) }
            }
        }

        // Una venta sin precio que mostrar, con la cantidad: sin ella, ya lo dice su etiqueta.
        card.intent == PhraseIntent.SALE && line.amount == null && line.quantity != null &&
            line.product is CardProduct.Chosen ->
            Text(
                text = stringResource(R.string.phrase_line_price_from_product),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        else -> Unit
    }
}

/**
 * `Último costo: …`, que lo toma como costo por unidad de la línea. Con el modo discreto el monto
 * no se ve: el botón solo dice que usa el último costo.
 */
@Composable
private fun LastCostButton(cost: Money, onClick: () -> Unit) {
    val text =
        if (LocalDiscreetMode.current) {
            stringResource(R.string.phrase_use_last_cost_hidden)
        } else {
            val shown = rememberMoneyDisplay(cost, LocalAppFormats.current.money).text.text
            stringResource(R.string.phrase_use_last_cost, shown)
        }
    InlineTextButton(text = text, onClick = onClick)
}

/** `Crear «…»` para un producto que se nombró y no existe, o `Elegir otro` del catálogo. */
@Composable
private fun CreateOrPick(name: String, onCreate: (String) -> Unit, onPick: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        InlineTextButton(
            text = stringResource(R.string.selection_create, name),
            onClick = { onCreate(name) },
        )
        InlineTextButton(text = stringResource(R.string.phrase_choose_other), onClick = onPick)
    }
}

/**
 * Lo que suma la línea, a la derecha: en una venta o una compra, el monto que pondrá la operación;
 * en un cambio de precio o de costo, el valor nuevo. Si no hay monto, nada: lo que falta lo dicen
 * las notas de la línea.
 */
@Composable
private fun LineAmount(card: PhraseCard, line: CardLine, pricedOnly: Boolean) {
    when {
        pricedOnly -> {
            val price = line.unitPrice
            if (price != null) {
                MoneyText(price, MoneySize.TITLE, sensitive = card.sensitive)
            } else {
                GapTag(
                    if (card.intent == PhraseIntent.COST_CORRECTION) {
                        R.string.phrase_cost_missing
                    } else {
                        R.string.phrase_price_missing
                    },
                )
            }
        }

        card.intent == PhraseIntent.SALE || card.intent == PhraseIntent.PURCHASE ->
            line.amount?.let { MoneyText(it, MoneySize.ROW, sensitive = card.sensitive) }

        // Producir, ajustar y una receta mueven cantidades, no dinero.
        else -> Unit
    }
}

/** El nombre del producto de la tarjeta; uno nuevo deja elegir su categoría, [onPickCategory]. */
@Composable
private fun ProductName(product: CardProduct, onPickCategory: () -> Unit = {}) {
    when (product) {
        is CardProduct.Chosen ->
            Text(text = product.name, style = MaterialTheme.typography.titleMedium)

        is CardProduct.New -> {
            Text(text = product.name, style = MaterialTheme.typography.titleMedium)
            if (product.needsCreating) {
                GapTag(R.string.phrase_line_create_first)
            } else {
                StatusTag(text = stringResource(R.string.phrase_new_tag), tone = StatusTone.INFO)
            }
            NewProductTraits(product, onPickCategory)
        }

        is CardProduct.Missing -> {
            product.spoken?.let {
                Text(
                    text = stringResource(R.string.phrase_heard, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GapTag(R.string.phrase_line_no_product)
        }
    }
}

/**
 * Lo que la frase dijo del producto nuevo, con lo que se va a crear: su uso, si es un servicio y su
 * categoría, una del catálogo o una nueva que se crea con él. `Elegir categoría` la cambia a mano.
 */
@Composable
private fun NewProductTraits(product: CardProduct.New, onPickCategory: () -> Unit) {
    val tags =
        listOfNotNull(
            product.usage?.let { stringResource(usageLabel(it)) },
            if (product.service) stringResource(R.string.term_service) else null,
            product.category?.let { stringResource(R.string.phrase_new_category, it) },
            product.newCategory?.let { stringResource(R.string.phrase_category_to_create, it) },
        )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        tags.forEach { StatusTag(text = it, tone = StatusTone.INFO) }
        InlineTextButton(
            text = stringResource(R.string.phrase_pick_category),
            onClick = onPickCategory,
        )
    }
}

/** Los parecidos en chips, `Elegir otro` y, si se puede, `Crear «…»` con lo que se dijo. */
@Composable
private fun MissingProduct(
    product: CardProduct.Missing,
    onChoose: (Long) -> Unit,
    onPick: () -> Unit,
    onCreate: (String) -> Unit,
) {
    if (product.candidates.isNotEmpty()) {
        CompactChipRow(
            options = product.candidates,
            label = { it.name },
            selected = emptySet(),
            onToggle = { onChoose(it.id) },
            multiple = false,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        InlineTextButton(
            text =
                stringResource(
                    if (product.candidates.isEmpty()) {
                        R.string.phrase_pick_product
                    } else {
                        R.string.phrase_choose_other
                    },
                ),
            onClick = onPick,
        )
        val spoken = product.spoken
        if (product.canCreate && spoken != null) {
            InlineTextButton(
                text = stringResource(R.string.selection_create, spoken),
                onClick = { onCreate(spoken) },
            )
        }
    }
}

/** El producto de la receta o el producto nuevo, con cuánto rinde la receta. */
@Composable
private fun TargetSection(
    card: PhraseCard,
    target: CardProduct,
    onAction: (PhraseAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
        SectionLabel(stringResource(R.string.phrase_target_label))
        when (target) {
            is CardProduct.Missing ->
                MissingProduct(
                    product = target,
                    onChoose = { onAction(PhraseAction.ChooseTarget(it)) },
                    onPick = { onAction(PhraseAction.PickTarget) },
                    onCreate = { onAction(PhraseAction.CreateTarget(it)) },
                )

            is CardProduct.New ->
                if (target.needsCreating) {
                    ProductName(target) { onAction(PhraseAction.PickNewCategory(null)) }
                    CreateOrPick(
                        name = target.name,
                        onCreate = { onAction(PhraseAction.CreateTarget(it)) },
                        onPick = { onAction(PhraseAction.PickTarget) },
                    )
                } else {
                    ChosenTarget(
                        target,
                        onPickCategory = { onAction(PhraseAction.PickNewCategory(null)) },
                    ) { onAction(PhraseAction.PickTarget) }
                }

            is CardProduct.Chosen -> ChosenTarget(target) { onAction(PhraseAction.PickTarget) }
        }
        if (target is CardProduct.Missing) GapTag(R.string.phrase_target_missing)
        val makes = card.targetQuantity
        if (card.intent == PhraseIntent.RECIPE && makes != null) {
            Text(
                text =
                    stringResource(
                        R.string.phrase_recipe_yield,
                        LocalAppFormats.current.quantity.format(makes, !makes.isWholeUnits),
                    ),
                style = AppTheme.amounts.row,
            )
        }
    }
}

/** El producto de la receta o el producto nuevo ya resuelto, con `Cambiar`. */
@Composable
private fun ChosenTarget(
    target: CardProduct,
    onPickCategory: () -> Unit = {},
    onChange: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            ProductName(target, onPickCategory)
        }
        InlineTextButton(text = stringResource(R.string.phrase_change), onClick = onChange)
    }
}

/** El precio, el costo y la unidad de un producto nuevo. El costo va en modo discreto. */
@Composable
private fun NewProductSection(card: PhraseCard, onAction: (PhraseAction) -> Unit) {
    AmountRow(
        label = stringResource(R.string.phrase_new_price),
        onClick = { onAction(PhraseAction.OpenAmount(AmountField.PRICE)) },
    ) {
        val price = card.price
        if (price != null) {
            MoneyText(price, MoneySize.TITLE)
        } else if (DraftGap.PRICE in card.gaps) {
            GapTag(R.string.phrase_price_missing)
        } else {
            Text(stringResource(R.string.phrase_add), style = MaterialTheme.typography.labelLarge)
        }
    }
    AmountRow(
        label = stringResource(R.string.phrase_new_cost),
        onClick = { onAction(PhraseAction.OpenAmount(AmountField.COST)) },
    ) {
        val cost = card.cost
        if (cost != null) {
            MoneyText(cost, MoneySize.TITLE, sensitive = true)
        } else if (DraftGap.COST in card.gaps) {
            GapTag(R.string.phrase_cost_missing)
        } else {
            Text(stringResource(R.string.phrase_add), style = MaterialTheme.typography.labelLarge)
        }
    }
    card.unit?.let { unit ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.phrase_unit),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(text = unit.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Una fila con su etiqueta a la izquierda y el monto a la derecha, que se toca para escribirlo. */
@Composable
private fun AmountRow(label: String, onClick: () -> Unit, value: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

@Composable
private fun CategorySection(
    category: CardChoice,
    gaps: Set<DraftGap>,
    onAction: (PhraseAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        SectionLabel(stringResource(R.string.phrase_category))
        if (DraftGap.CATEGORY in gaps) GapTag(R.string.phrase_category_missing)
        CompactChipRow(
            options = category.options,
            label = { it.name },
            selected = category.options.filter { it.id == category.selectedId }.toSet(),
            onToggle = { onAction(PhraseAction.ChooseCategory(it.id)) },
            multiple = false,
        )
    }
}

/**
 * El método de pago en chips, uno elegido. `A crédito` es uno de ellos, el que trae la app:
 * elegirlo deja la venta o la compra a crédito.
 */
@Composable
private fun PaymentSection(
    card: PhraseCard,
    payment: CardChoice,
    onAction: (PhraseAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        SectionLabel(stringResource(R.string.phrase_payment))
        if (DraftGap.PAYMENT in card.gaps) GapTag(R.string.phrase_payment_missing)
        CompactChipRow(
            options = payment.options,
            label = { it.name },
            selected = payment.options.filter { it.id == payment.selectedId }.toSet(),
            onToggle = { onAction(PhraseAction.ChoosePayment(it.id)) },
            multiple = false,
        )
    }
}

/**
 * El cliente o el proveedor: el elegido con `Cambiar`, o los parecidos para elegir. Un nombre que
 * no está en la lista se dice tal cual, sin adivinar a quién se refería.
 */
@Composable
private fun PartySection(card: PhraseCard, party: CardParty, onAction: (PhraseAction) -> Unit) {
    val spacing = AppTheme.spacing
    val missing = DraftGap.PARTY in card.gaps
    val chosen = party.chosen
    // En una venta o una compra la persona es opcional: sin nada dicho, solo se ofrece elegirla.
    val pick = if (party.supplier) R.string.phrase_pick_supplier else R.string.phrase_pick_customer
    if (chosen == null && party.candidates.isEmpty() && party.spoken == null && !missing) {
        InlineTextButton(
            text = stringResource(pick),
            onClick = { onAction(PhraseAction.PickParty) },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        val label = if (party.supplier) R.string.phrase_supplier else R.string.phrase_customer
        SectionLabel(stringResource(label))
        if (chosen != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chosen.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                InlineTextButton(
                    text = stringResource(R.string.phrase_change),
                    onClick = { onAction(PhraseAction.PickParty) },
                )
                if (!missing && card.intent in OPTIONAL_PARTY) {
                    InlineTextButton(
                        text = stringResource(R.string.phrase_remove),
                        onClick = { onAction(PhraseAction.ChooseParty(null)) },
                    )
                }
            }
            return@Column
        }
        party.spoken?.let {
            Text(
                text = stringResource(R.string.phrase_party_unknown, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (missing) {
            GapTag(
                if (party.supplier) {
                    R.string.phrase_supplier_missing
                } else {
                    R.string.phrase_customer_missing
                },
            )
        }
        if (party.candidates.isNotEmpty()) {
            CompactChipRow(
                options = party.candidates,
                label = { it.name },
                selected = emptySet(),
                onToggle = { onAction(PhraseAction.ChooseParty(it.id)) },
                multiple = false,
            )
        }
        InlineTextButton(
            text = stringResource(pick),
            onClick = { onAction(PhraseAction.PickParty) },
        )
    }
}

private val OPTIONAL_PARTY = setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE)

/**
 * El total, grande y a la derecha, al pie de la tarjeta: en una venta o una compra, el dicho o la
 * suma de las líneas con lo que pondrá la operación; en un gasto o un pago, el monto. Se toca para
 * escribirlo. Solo si alguna línea no deja saberlo, se dice qué falta.
 */
@Composable
private fun TotalSection(card: PhraseCard, onAction: (PhraseAction) -> Unit) {
    val summed = card.intent == PhraseIntent.SALE || card.intent == PhraseIntent.PURCHASE
    val single =
        card.intent == PhraseIntent.EXPENSE ||
            card.intent == PhraseIntent.CUSTOMER_PAYMENT ||
            card.intent == PhraseIntent.SUPPLIER_PAYMENT
    if (!summed && !single) return
    HorizontalDivider(
        thickness = AppTheme.sizes.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    val total = card.total
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(role = Role.Button) {
                onAction(PhraseAction.OpenAmount(AmountField.TOTAL))
            },
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = if (summed) R.string.phrase_total else R.string.phrase_amount
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            total?.let { MoneyText(it, MoneySize.LARGE, sensitive = card.sensitive) }
        }
        when {
            DraftGap.AMOUNT in card.gaps -> GapTag(R.string.phrase_amount_missing)

            total == null ->
                Text(
                    text = stringResource(R.string.phrase_total_at_register),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            else -> Unit
        }
    }
}

/** Las hojas sobre la tarjeta: corregir una línea, escribir un monto o elegir de una lista. */
@Composable
private fun PhraseSheets(uiState: PhraseUiState, onAction: (PhraseAction) -> Unit) {
    val card = (uiState.stage as? PhraseStage.Card)?.card ?: return
    when (val sheet = uiState.sheet) {
        is PhraseSheet.Line -> LineSheet(card, sheet, onAction)
        is PhraseSheet.Amount -> AmountSheet(card, sheet, onAction)
        is PhraseSheet.Pick -> PickSheet(card, sheet, onAction)
        null -> Unit
    }
}

@Composable
private fun LineSheet(
    card: PhraseCard,
    sheet: PhraseSheet.Line,
    onAction: (PhraseAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val quantityLabel = sheet.unit?.let { unitLabelOf(it, fraction = true) } ?: UnitLabel("", true)
    AppBottomSheet(
        onDismissRequest = { onAction(PhraseAction.CloseSheet) },
        title = sheet.title ?: stringResource(R.string.phrase_line_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.phrase_done),
                onClick = { onAction(PhraseAction.SaveLine) },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
            if (sheet.showsQuantity) {
                QuantityField(
                    value = sheet.quantityText,
                    onValueChange = { onAction(PhraseAction.LineQuantityChange(it)) },
                    label = stringResource(R.string.phrase_quantity),
                    unit = quantityLabel,
                    error =
                        if (sheet.quantityError) {
                            stringResource(R.string.phrase_quantity_invalid)
                        } else {
                            null
                        },
                    imeAction = if (sheet.showsPrice) ImeAction.Next else ImeAction.Done,
                )
            }
            if (sheet.showsPrice) {
                MoneyField(
                    value = sheet.priceText,
                    onValueChange = { onAction(PhraseAction.LinePriceChange(it)) },
                    label = stringResource(pricedLabel(card.intent)),
                    error =
                        if (sheet.priceError) {
                            stringResource(R.string.phrase_amount_invalid)
                        } else {
                            null
                        },
                    imeAction = ImeAction.Done,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s8)) {
                InlineTextButton(
                    text = stringResource(R.string.phrase_change_product),
                    onClick = { onAction(PhraseAction.PickProduct(sheet.index)) },
                )
                InlineTextButton(
                    text = stringResource(R.string.phrase_remove_line),
                    onClick = { onAction(PhraseAction.RemoveLine) },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AmountSheet(
    card: PhraseCard,
    sheet: PhraseSheet.Amount,
    onAction: (PhraseAction) -> Unit,
) {
    val label =
        when (sheet.field) {
            AmountField.TOTAL ->
                if (card.intent == PhraseIntent.SALE || card.intent == PhraseIntent.PURCHASE) {
                    R.string.phrase_total
                } else {
                    R.string.phrase_amount
                }

            AmountField.PRICE -> R.string.phrase_new_price

            AmountField.COST -> R.string.phrase_new_cost
        }
    AppBottomSheet(
        onDismissRequest = { onAction(PhraseAction.CloseSheet) },
        title = stringResource(label),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.phrase_done),
                onClick = { onAction(PhraseAction.SaveAmount) },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        MoneyField(
            value = sheet.text,
            onValueChange = { onAction(PhraseAction.AmountChange(it)) },
            label = stringResource(label),
            error = if (sheet.error) stringResource(R.string.phrase_amount_invalid) else null,
            imeAction = ImeAction.Done,
        )
    }
}

@Composable
private fun PickSheet(
    card: PhraseCard,
    sheet: PhraseSheet.Pick,
    onAction: (PhraseAction) -> Unit,
) {
    val supplier = card.party?.supplier == true
    val title =
        when (sheet.purpose) {
            is PickPurpose.LineProduct, PickPurpose.Target -> R.string.phrase_pick_product

            is PickPurpose.NewCategory -> R.string.phrase_pick_category

            PickPurpose.Party ->
                if (supplier) R.string.phrase_pick_supplier else R.string.phrase_pick_customer
        }
    SelectionSheet(
        title = stringResource(title),
        items = sheet.items,
        key = { it.id },
        query = sheet.query,
        onQueryChange = { onAction(PhraseAction.QueryChange(it)) },
        onDismissRequest = { onAction(PhraseAction.CloseSheet) },
        searchHint = stringResource(R.string.phrase_search_hint),
        itemContent = { option ->
            Text(
                text = option.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppTheme.sizes.listRowSingle)
                        .clickable(role = Role.Button) {
                            onAction(
                                when (val purpose = sheet.purpose) {
                                    is PickPurpose.LineProduct ->
                                        PhraseAction.ChooseProduct(purpose.index, option.id)

                                    PickPurpose.Target -> PhraseAction.ChooseTarget(option.id)

                                    is PickPurpose.NewCategory ->
                                        PhraseAction.ChooseNewCategory(purpose.index, option.id)

                                    PickPurpose.Party -> PhraseAction.ChooseParty(option.id)
                                },
                            )
                        }.padding(vertical = AppTheme.spacing.s16),
            )
        },
        onCreate =
            if (sheet.canCreate) {
                { name ->
                    onAction(
                        when (val purpose = sheet.purpose) {
                            is PickPurpose.LineProduct ->
                                PhraseAction.CreateProduct(purpose.index, name)

                            is PickPurpose.NewCategory ->
                                PhraseAction.NameNewCategory(purpose.index, name)

                            // A una persona no se la crea desde aquí: la hoja no lo ofrece.
                            PickPurpose.Target, PickPurpose.Party -> PhraseAction.CreateTarget(name)
                        },
                    )
                }
            } else {
                null
            },
    )
}

/** La cantidad con su unidad, con el formato del negocio: `3 und`, `0,5 kg` o `2 × caja`. */
@Composable
private fun lineQuantity(quantity: QuantityMilli, unit: CardUnit?): String {
    if (unit == null) {
        return LocalAppFormats.current.quantity.format(quantity, !quantity.isWholeUnits)
    }
    return quantityText(quantity, unitLabelOf(unit, fraction = !quantity.isWholeUnits))
}

/**
 * Cómo acompaña [unit] a una cantidad: su símbolo, traducido si es de las que trae la app, o su
 * nombre. Una que se cuenta admite decimales solo si [fraction].
 */
@Composable
private fun unitLabelOf(unit: CardUnit, fraction: Boolean): UnitLabel = UnitLabel(
    symbol = unitText(unit),
    allowsFraction = unit.kind != UnitKind.COUNT || fraction,
    custom = unit.systemKey == null,
)

@Composable
private fun unitText(unit: CardUnit): String =
    SeedText.unitSymbol(LocalResources.current, unit.systemKey, unit.symbol) ?: unit.name

@StringRes
private fun intentLabel(intent: PhraseIntent, reason: StockAdjustmentReason?): Int = when (intent) {
    PhraseIntent.SALE -> R.string.phrase_intent_sale

    PhraseIntent.PURCHASE -> R.string.phrase_intent_purchase

    PhraseIntent.EXPENSE -> R.string.phrase_intent_expense

    PhraseIntent.PRODUCTION -> R.string.phrase_intent_production

    PhraseIntent.STOCK_ADJUSTMENT ->
        if (reason == StockAdjustmentReason.COUNT_CORRECTION) {
            R.string.phrase_intent_stock_count
        } else {
            R.string.phrase_intent_stock_adjustment
        }

    PhraseIntent.CUSTOMER_PAYMENT -> R.string.phrase_intent_customer_payment

    PhraseIntent.SUPPLIER_PAYMENT -> R.string.phrase_intent_supplier_payment

    PhraseIntent.RECIPE -> R.string.phrase_intent_recipe

    PhraseIntent.NEW_PRODUCT -> R.string.phrase_intent_new_product

    PhraseIntent.COST_CORRECTION -> R.string.phrase_intent_cost_correction

    PhraseIntent.PRICE_CHANGE -> R.string.phrase_intent_price_change
}

@StringRes
private fun noticeText(issue: PhraseIssue): Int = when (issue) {
    PhraseIssue.UNREADABLE_QUANTITY -> R.string.phrase_issue_unreadable_quantity

    PhraseIssue.UNIT_NOT_FOR_PRODUCT -> R.string.phrase_issue_unit_not_for_product

    PhraseIssue.NO_RECIPE -> R.string.phrase_issue_no_recipe

    PhraseIssue.NO_STOCK -> R.string.phrase_issue_no_stock

    PhraseIssue.ALREADY_EXISTS -> R.string.phrase_issue_already_exists

    PhraseIssue.UNUSED_WORDS -> R.string.phrase_issue_unused_words

    PhraseIssue.COST_LEFT_OUT -> R.string.phrase_issue_cost_left_out

    // No llegan a la tarjeta: tienen su propio estado.
    PhraseIssue.MIXED_OPERATIONS -> R.string.phrase_mixed

    PhraseIssue.NOTHING_UNDERSTOOD -> R.string.phrase_not_understood
}

@StringRes
private fun otherOperationText(intent: PhraseIntent): Int = when (intent) {
    PhraseIntent.PURCHASE -> R.string.phrase_line_not_for_purchase

    PhraseIntent.PRODUCTION -> R.string.phrase_issue_no_recipe

    PhraseIntent.SALE,
    PhraseIntent.EXPENSE,
    PhraseIntent.STOCK_ADJUSTMENT,
    PhraseIntent.CUSTOMER_PAYMENT,
    PhraseIntent.SUPPLIER_PAYMENT,
    PhraseIntent.RECIPE,
    PhraseIntent.NEW_PRODUCT,
    PhraseIntent.COST_CORRECTION,
    PhraseIntent.PRICE_CHANGE,
    -> R.string.phrase_line_not_for_sale
}

/** El nombre del precio de una línea: por unidad en la venta, costo por unidad en la compra. */
@StringRes
private fun pricedLabel(intent: PhraseIntent): Int = when (intent) {
    PhraseIntent.PURCHASE, PhraseIntent.COST_CORRECTION -> R.string.phrase_unit_cost

    PhraseIntent.SALE,
    PhraseIntent.PRICE_CHANGE,
    PhraseIntent.EXPENSE,
    PhraseIntent.PRODUCTION,
    PhraseIntent.STOCK_ADJUSTMENT,
    PhraseIntent.CUSTOMER_PAYMENT,
    PhraseIntent.SUPPLIER_PAYMENT,
    PhraseIntent.RECIPE,
    PhraseIntent.NEW_PRODUCT,
    -> R.string.phrase_unit_price
}
