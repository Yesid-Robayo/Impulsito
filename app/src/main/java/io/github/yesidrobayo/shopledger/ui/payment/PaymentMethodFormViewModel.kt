package io.github.yesidrobayo.shopledger.ui.payment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.payment.InstructionsImageChange
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ArchivePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ArchivePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.CreatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ImportPaymentImageUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.LoadPaymentMethodOptionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveCashRoundingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObservePaymentMethodsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReactivatePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReactivatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Los casos de uso que necesita el formulario de un método de pago. */
class PaymentMethodFormUseCases(
    val observe: ObservePaymentMethodsUseCase,
    val loadOptions: LoadPaymentMethodOptionsUseCase,
    val create: CreatePaymentMethodUseCase,
    val edit: EditPaymentMethodUseCase,
    val archive: ArchivePaymentMethodUseCase,
    val reactivate: ReactivatePaymentMethodUseCase,
    val delete: DeletePaymentMethodUseCase,
    val observeCashRounding: ObserveCashRoundingUseCase,
    val importImage: ImportPaymentImageUseCase,
)

/**
 * El formulario para crear un método de pago, si [methodId] es `null`, o para editar uno, con sus
 * opciones de archivar, reactivar y eliminar.
 *
 * Lo escrito vive aquí como texto y se lee con el formato regional de [numbers] solo al guardar. Un
 * error, del campo o de la base, nunca borra lo escrito. Cada caso de uso vuelve a comprobar todo al
 * escribir: lo que se ofrece aquí es solo lo que tiene sentido ofrecer.
 */
class PaymentMethodFormViewModel(
    private val methodId: Long?,
    private val useCases: PaymentMethodFormUseCases,
    private val numbers: FormNumbers,
) : ViewModel() {
    private val state = MutableStateFlow<PaymentMethodFormUiState>(PaymentMethodFormUiState.Loading)

    /** El último redondeo leído, para el formulario que todavía carga cuando llega. */
    private var cashRounding: Money = Money.ZERO

    val uiState: StateFlow<PaymentMethodFormUiState> = state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            useCases.observe(ArchiveFilter.ACTIVE).collect { active ->
                val initial =
                    active
                        .filter { it.details.name == null && it.id != methodId }
                        .mapNotNull { method -> method.systemKey?.let { it to method.id } }
                        .toMap()
                updateEditing { it.copy(initialMethods = initial) }
            }
        }
        viewModelScope.launch {
            useCases.observeCashRounding().collect { step ->
                cashRounding = step
                updateEditing { it.copy(cashRounding = step) }
            }
        }
    }

    fun onAction(action: PaymentMethodFormAction) {
        when (action) {
            is PaymentMethodFormAction.NameChanged ->
                edit(FormField.NAME) { it.copy(name = action.value, nameEdited = true) }

            is PaymentMethodFormAction.KindChanged -> kindChanged(action.kind)

            is PaymentMethodFormAction.FeeRateChanged ->
                edit(FormField.FEE_RATE) { it.copy(feeRate = action.value) }

            is PaymentMethodFormAction.FeeFixedChanged ->
                edit(FormField.FEE_FIXED) { it.copy(feeFixed = action.value) }

            is PaymentMethodFormAction.SettlementDaysChanged ->
                edit(FormField.SETTLEMENT_DAYS) { it.copy(settlementDays = action.value) }

            is PaymentMethodFormAction.PriceAdjustmentChanged ->
                edit(FormField.PRICE_ADJUSTMENT) { it.copy(priceAdjustment = action.value) }

            is PaymentMethodFormAction.InstructionsChanged ->
                edit(FormField.INSTRUCTIONS) { it.copy(instructions = action.value) }

            PaymentMethodFormAction.Save -> save()

            PaymentMethodFormAction.AskArchive -> askFor(FormDialog.ARCHIVE)

            PaymentMethodFormAction.AskDelete -> askFor(FormDialog.DELETE)

            PaymentMethodFormAction.DismissDialog -> updateEditing { it.copy(dialog = null) }

            PaymentMethodFormAction.ConfirmDialog -> confirmDialog()

            PaymentMethodFormAction.Reactivate -> reactivate()

            is PaymentMethodFormAction.PickImage -> importImage(action.source)

            PaymentMethodFormAction.RemoveImage ->
                updateEditing {
                    it.copy(form = it.form.copy(image = FormImage.None), imageProblem = null)
                }

            // La navegación la resuelve la ruta.
            PaymentMethodFormAction.Back,
            is PaymentMethodFormAction.OpenMethod,
            -> Unit
        }
    }

    private fun load() {
        if (methodId == null) {
            state.value =
                PaymentMethodFormUiState.Editing(
                    methodId = null,
                    systemKey = null,
                    form = PaymentMethodForm(nameEdited = true),
                    options = null,
                    cashRounding = cashRounding,
                )
            return
        }
        viewModelScope.launch {
            val options = useCases.loadOptions(methodId)
            state.value =
                if (options == null) {
                    PaymentMethodFormUiState.NotFound
                } else {
                    PaymentMethodFormUiState.Editing(
                        methodId = methodId,
                        systemKey = options.method.systemKey,
                        form =
                            formOf(options.method).copy(
                                image = options.image?.let(FormImage::Saved) ?: FormImage.None,
                            ),
                        options = options,
                        cashRounding = cashRounding,
                    )
                }
        }
    }

    private fun formOf(method: PaymentMethodRecord): PaymentMethodForm {
        val details = method.details
        val percent = numbers.percentSymbols
        return PaymentMethodForm(
            name = details.name.orEmpty(),
            nameEdited = details.name != null,
            kind = details.kind,
            feeRate = blankIfZero(details.feeRate.bp) {
                plainNumberText(it, PERCENT_DECIMALS, percent)
            },
            feeFixed =
                blankIfZero(details.feeFixed.minor) {
                    plainNumberText(it, numbers.currencyDecimals, numbers.moneySymbols)
                },
            settlementDays = blankIfZero(details.settlementDays.toLong()) { it.toString() },
            priceAdjustment =
                blankIfZero(details.priceAdjustment.bp) {
                    plainNumberText(it, PERCENT_DECIMALS, percent)
                },
            instructions = details.instructionsText.orEmpty(),
        )
    }

    private fun kindChanged(kind: PaymentMethodKind) {
        val editing = state.value as? PaymentMethodFormUiState.Editing ?: return
        // Con ventas el tipo queda como está: la pantalla no lo ofrece, y aquí tampoco cambia.
        if (editing.options?.isKindLocked == true) return
        edit(FormField.KIND) { it.copy(kind = kind) }
    }

    private fun edit(field: FormField, change: (PaymentMethodForm) -> PaymentMethodForm) {
        updateEditing { it.copy(form = change(it.form), errors = it.errors - field, notice = null) }
    }

    private fun askFor(dialog: FormDialog) {
        updateEditing { editing ->
            when {
                editing.options?.isLastActive == true ->
                    editing.copy(notice = FormNotice.LastActive)

                else -> editing.copy(dialog = dialog, notice = null)
            }
        }
    }

    private fun save() {
        val editing = state.value as? PaymentMethodFormUiState.Editing ?: return
        if (editing.working) return
        val read = readForm(editing.form, editing.systemKey != null)
        if (read.errors.isNotEmpty() || read.draft == null) {
            updateEditing { it.copy(errors = read.errors, notice = null) }
            return
        }
        updateEditing { it.copy(working = true, errors = emptyMap(), notice = null) }
        viewModelScope.launch {
            val result =
                if (methodId == null) {
                    useCases.create(read.draft)
                } else {
                    useCases.edit(methodId, read.draft)
                }
            updateEditing { current ->
                val idle = current.copy(working = false)
                when (result) {
                    is SavePaymentMethodResult.Saved -> idle.copy(finished = true)

                    is SavePaymentMethodResult.Invalid -> withInputErrors(idle, result.errors)

                    SavePaymentMethodResult.NotFound -> return@updateEditing NOT_FOUND

                    is SavePaymentMethodResult.StorageFailed ->
                        idle.copy(
                            notice = FormNotice.SaveFailed(
                                SaveProblem.of(result.failure.isOutOfSpace),
                            ),
                        )
                }
            }
        }
    }

    private fun confirmDialog() {
        val editing = state.value as? PaymentMethodFormUiState.Editing ?: return
        val id = editing.methodId ?: return
        val dialog = editing.dialog ?: return
        updateEditing { it.copy(dialog = null, working = true, notice = null) }
        viewModelScope.launch {
            val outcome: (PaymentMethodFormUiState.Editing) -> PaymentMethodFormUiState =
                when (dialog) {
                    FormDialog.ARCHIVE -> archiveOutcome(useCases.archive(id))
                    FormDialog.DELETE -> deleteOutcome(useCases.delete(id))
                }
            state.update { current ->
                if (current is PaymentMethodFormUiState.Editing) {
                    outcome(current.copy(working = false))
                } else {
                    current
                }
            }
        }
    }

    private fun archiveOutcome(
        result: ArchivePaymentMethodResult,
    ): (PaymentMethodFormUiState.Editing) -> PaymentMethodFormUiState = { editing ->
        when (result) {
            // Ya archivado da lo mismo: la lista lo muestra con los archivados.
            ArchivePaymentMethodResult.Archived,
            ArchivePaymentMethodResult.AlreadyArchived,
            -> editing.copy(finished = true)

            ArchivePaymentMethodResult.NotFound -> NOT_FOUND

            ArchivePaymentMethodResult.LastActive -> editing.copy(notice = FormNotice.LastActive)

            is ArchivePaymentMethodResult.StorageFailed ->
                editing.copy(
                    notice = FormNotice.SaveFailed(SaveProblem.of(result.failure.isOutOfSpace)),
                )
        }
    }

    private fun deleteOutcome(
        result: DeletePaymentMethodResult,
    ): (PaymentMethodFormUiState.Editing) -> PaymentMethodFormUiState = { editing ->
        when (result) {
            DeletePaymentMethodResult.Deleted -> editing.copy(finished = true)

            DeletePaymentMethodResult.NotFound -> NOT_FOUND

            DeletePaymentMethodResult.HasSalePayments -> editing.copy(notice = FormNotice.HasSales)

            DeletePaymentMethodResult.LastActive -> editing.copy(notice = FormNotice.LastActive)

            is DeletePaymentMethodResult.StorageFailed ->
                editing.copy(
                    notice = FormNotice.SaveFailed(SaveProblem.of(result.failure.isOutOfSpace)),
                )
        }
    }

    private fun reactivate() {
        val editing = state.value as? PaymentMethodFormUiState.Editing ?: return
        val id = editing.methodId ?: return
        if (editing.working) return
        updateEditing { it.copy(working = true, notice = null) }
        viewModelScope.launch {
            val result = useCases.reactivate(id)
            state.update { current ->
                val form = current as? PaymentMethodFormUiState.Editing ?: return@update current
                val idle = form.copy(working = false)
                when (result) {
                    ReactivatePaymentMethodResult.Reactivated,
                    ReactivatePaymentMethodResult.NotArchived,
                    -> idle.copy(finished = true)

                    ReactivatePaymentMethodResult.NotFound -> NOT_FOUND

                    ReactivatePaymentMethodResult.NameTaken ->
                        idle.copy(notice = FormNotice.ReactivateNameTaken)

                    is ReactivatePaymentMethodResult.StorageFailed ->
                        idle.copy(
                            notice = FormNotice.SaveFailed(
                                SaveProblem.of(result.failure.isOutOfSpace),
                            ),
                        )
                }
            }
        }
    }

    /** Lo escrito como borrador, o los errores de los campos que no se pudieron leer. */
    private class ReadForm(val draft: PaymentMethodDraft?, val errors: Map<FormField, FieldProblem>)

    private fun readForm(form: PaymentMethodForm, isInitial: Boolean): ReadForm {
        val errors = mutableMapOf<FormField, FieldProblem>()
        val feeRate =
            readPercent(form.feeRate, PaymentMethodRules.FEE_RATE_RANGE.endInclusive) ?: run {
                errors[FormField.FEE_RATE] = FieldProblem.FEE_RATE_RANGE
                BasisPoints.ZERO
            }
        val adjustment =
            readPercent(
                form.priceAdjustment,
                PaymentMethodRules.PRICE_ADJUSTMENT_RANGE.endInclusive,
            )
                ?: run {
                    errors[FormField.PRICE_ADJUSTMENT] = FieldProblem.PRICE_ADJUSTMENT_RANGE
                    BasisPoints.ZERO
                }
        val feeFixed =
            when (
                val read =
                    NumberInputParser.money(
                        form.feeFixed,
                        numbers.moneySymbols,
                        numbers.currencyDecimals,
                        Limit.UNIT_MONEY,
                    )
            ) {
                is NumberInput.Value -> read.value

                NumberInput.Blank -> Money.ZERO

                is NumberInput.TooLarge -> {
                    errors[FormField.FEE_FIXED] = FieldProblem.FEE_FIXED_TOO_HIGH
                    Money.ZERO
                }

                NumberInput.Malformed, is NumberInput.TooManyDecimals -> {
                    errors[FormField.FEE_FIXED] = FieldProblem.FEE_FIXED_NEGATIVE
                    Money.ZERO
                }
            }
        val days = readDays(form.settlementDays) ?: run {
            errors[FormField.SETTLEMENT_DAYS] = FieldProblem.SETTLEMENT_TOO_HIGH
            0
        }
        // Las cifras se leen aquí; lo demás lo revisa el caso de uso, con los mismos rangos.
        val onAccount = form.kind == PaymentMethodKind.ON_ACCOUNT
        if (onAccount) {
            errors -= setOf(FormField.FEE_RATE, FormField.FEE_FIXED, FormField.SETTLEMENT_DAYS)
        }
        if (errors.isNotEmpty()) return ReadForm(null, errors)
        val draft =
            PaymentMethodDraft(
                name = if (isInitial && !form.nameEdited) null else form.name,
                kind = form.kind,
                feeRate = feeRate,
                feeFixed = feeFixed,
                settlementDays = days,
                priceAdjustment = adjustment,
                moneyAccountId = currentAccount(),
                instructionsText = form.instructions,
                image = imageChange(form.image),
            )
        return ReadForm(draft, emptyMap())
    }

    /**
     * Qué pasa con la imagen al guardar: la elegida se asigna; sin imagen, se quita la que tenía; la
     * que ya estaba queda igual.
     */
    private fun imageChange(image: FormImage): InstructionsImageChange = when (image) {
        is FormImage.Picked -> InstructionsImageChange.Use(image.image)

        is FormImage.Saved -> InstructionsImageChange.Keep

        FormImage.None ->
            if (currentImageId() == null) {
                InstructionsImageChange.Keep
            } else {
                InstructionsImageChange.Remove
            }
    }

    private fun currentImageId(): Long? = (state.value as? PaymentMethodFormUiState.Editing)
        ?.options
        ?.method
        ?.details
        ?.instructionsMediaId

    /**
     * Trae la imagen elegida, recodificada y sin EXIF, sin asignarla todavía: se asigna al guardar,
     * con lo demás del formulario.
     */
    private fun importImage(source: String) {
        val editing = state.value as? PaymentMethodFormUiState.Editing ?: return
        if (editing.importingImage) return
        updateEditing { it.copy(importingImage = true, imageProblem = null) }
        viewModelScope.launch {
            val result = useCases.importImage(source)
            updateEditing { current ->
                val idle = current.copy(importingImage = false)
                when (result) {
                    is ImageImport.Imported ->
                        idle.copy(form = idle.form.copy(image = FormImage.Picked(result.image)))

                    ImageImport.Unreadable -> idle.copy(imageProblem = ImageProblem.UNREADABLE)

                    ImageImport.OutOfSpace -> idle.copy(imageProblem = ImageProblem.OUT_OF_SPACE)
                }
            }
        }
    }

    /** La cuenta que ya tenía el método: este formulario todavía no la cambia. */
    private fun currentAccount(): Long? =
        (state.value as? PaymentMethodFormUiState.Editing)?.options?.method?.details?.moneyAccountId

    private fun readPercent(text: String, maximum: BasisPoints): BasisPoints? =
        when (val read = NumberInputParser.percent(text, numbers.percentSymbols, maximum)) {
            is NumberInput.Value -> read.value

            NumberInput.Blank -> BasisPoints.ZERO

            NumberInput.Malformed,
            is NumberInput.TooManyDecimals,
            is NumberInput.TooLarge,
            -> null
        }

    /** Los días escritos, que el campo ya dejó solo en cifras, o `null` si pasan del tope. */
    private fun readDays(text: String): Int? {
        if (text.isBlank()) return 0
        // Más cifras que las del tope ya no caben: se rechazan sin convertir.
        if (text.length > MAX_DAY_DIGITS) return null
        return text.toIntOrNull()?.takeIf { it <= PaymentMethodRules.MAX_SETTLEMENT_DAYS }
    }

    private fun withInputErrors(
        editing: PaymentMethodFormUiState.Editing,
        errors: Set<PaymentMethodInputError>,
    ): PaymentMethodFormUiState.Editing {
        val fields = mutableMapOf<FormField, FieldProblem>()
        var notice: FormNotice? = null
        for (error in errors) {
            when (error) {
                PaymentMethodInputError.NAME_BLANK -> fields[FormField.NAME] =
                    FieldProblem.NAME_BLANK

                PaymentMethodInputError.NAME_TOO_LONG ->
                    fields[FormField.NAME] = FieldProblem.NAME_TOO_LONG

                PaymentMethodInputError.NAME_TAKEN -> fields[FormField.NAME] =
                    FieldProblem.NAME_TAKEN

                PaymentMethodInputError.FEE_RATE_OUT_OF_RANGE ->
                    fields[FormField.FEE_RATE] = FieldProblem.FEE_RATE_RANGE

                PaymentMethodInputError.FEE_FIXED_OUT_OF_RANGE ->
                    fields[FormField.FEE_FIXED] = FieldProblem.FEE_FIXED_TOO_HIGH

                PaymentMethodInputError.SETTLEMENT_DAYS_OUT_OF_RANGE ->
                    fields[FormField.SETTLEMENT_DAYS] = FieldProblem.SETTLEMENT_TOO_HIGH

                PaymentMethodInputError.PRICE_ADJUSTMENT_OUT_OF_RANGE ->
                    fields[FormField.PRICE_ADJUSTMENT] = FieldProblem.PRICE_ADJUSTMENT_RANGE

                PaymentMethodInputError.INSTRUCTIONS_TOO_LONG ->
                    fields[FormField.INSTRUCTIONS] = FieldProblem.INSTRUCTIONS_TOO_LONG

                PaymentMethodInputError.KIND_LOCKED -> fields[FormField.KIND] =
                    FieldProblem.KIND_LOCKED

                PaymentMethodInputError.MONEY_ACCOUNT_UNAVAILABLE ->
                    notice = FormNotice.AccountUnavailable
            }
        }
        return editing.copy(errors = fields, notice = notice)
    }

    private fun updateEditing(
        change: (PaymentMethodFormUiState.Editing) -> PaymentMethodFormUiState,
    ) {
        state.update { current ->
            if (current is PaymentMethodFormUiState.Editing) change(current) else current
        }
    }

    private companion object {
        const val PERCENT_DECIMALS = 2

        /** Cifras de [PaymentMethodRules.MAX_SETTLEMENT_DAYS]. */
        val MAX_DAY_DIGITS = PaymentMethodRules.MAX_SETTLEMENT_DAYS.toString().length

        val NOT_FOUND: PaymentMethodFormUiState = PaymentMethodFormUiState.NotFound

        /** Un campo en cero se muestra vacío: vacío también se guarda como cero. */
        inline fun blankIfZero(value: Long, text: (Long) -> String): String =
            if (value == 0L) "" else text(value)
    }
}
