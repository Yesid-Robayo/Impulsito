package io.github.yesidrobayo.shopledger.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.customer.LoyaltyProgramOverview
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveLoyaltyProgramResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cómo se leen y se escriben los montos: los símbolos y los decimales de la moneda del negocio. */
data class LoyaltyNumbers(val symbols: NumberSymbols, val currencyDecimals: Int)

/**
 * El programa de puntos y sus acciones.
 *
 * Lo guardado llega de la base; lo escrito vive aquí hasta guardarlo, y una falla nunca lo borra.
 * Encender el interruptor solo abre el formulario: el programa se activa al guardar con sus dos
 * montos. Apagar un programa activo pide confirmar y se guarda enseguida, con los montos de antes,
 * para no pedirlos otra vez.
 */
class LoyaltyProgramViewModel(
    observe: () -> Flow<LoyaltyProgramOverview>,
    private val save: suspend (LoyaltySettings) -> SaveLoyaltyProgramResult,
    private val numbers: LoyaltyNumbers,
) : ViewModel() {
    private val reads = ScreenReads()
    private val overview = MutableStateFlow<LoyaltyProgramOverview?>(null)
    private val local = MutableStateFlow(Local())

    /** Lo guardado: una falla se dice con `Reintentar`, y la pantalla no se cierra. */
    private val current: StateFlow<LoyaltyProgramOverview?> =
        reads.guard(observe)
            .onEach(::received)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<LoyaltyProgramUiState> =
        combine(current, local, reads.failed) { saved, shown, failed ->
            stateOf(saved, shown, failed)
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                LoyaltyProgramUiState.Loading,
            )

    fun onAction(action: LoyaltyProgramAction) {
        when (action) {
            // Volver y abrir clientes los resuelve la navegación.
            LoyaltyProgramAction.Back, LoyaltyProgramAction.OpenCustomers -> Unit

            LoyaltyProgramAction.Retry -> reads.retry()

            is LoyaltyProgramAction.SetEnabled -> setEnabled(action.enabled)

            is LoyaltyProgramAction.AmountPerPoint -> editForm {
                it.copy(amountPerPoint = action.text, amountPerPointError = null)
            }

            is LoyaltyProgramAction.PointValue -> editForm {
                it.copy(pointValue = action.text, pointValueError = null)
            }

            LoyaltyProgramAction.Save -> saveForm()

            LoyaltyProgramAction.ConfirmTurnOff -> {
                local.update { it.copy(confirmTurnOff = false) }
                turnOff()
            }

            LoyaltyProgramAction.DismissTurnOff -> local.update { it.copy(confirmTurnOff = false) }

            LoyaltyProgramAction.MessageShown -> local.update { it.copy(savedMessage = false) }
        }
    }

    /** El primer programa leído llena el formulario; después, solo si nadie lo tocó. */
    private fun received(current: LoyaltyProgramOverview) {
        overview.value = current
        local.update { shown ->
            if (shown.form == null ||
                !shown.edited
            ) {
                shown.copy(form = formOf(current.settings))
            } else {
                shown
            }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        val savedEnabled = overview.value?.settings?.enabled ?: return
        if (!enabled && savedEnabled) {
            local.update { it.copy(confirmTurnOff = true) }
        } else {
            editForm { it.copy(enabled = enabled) }
        }
    }

    private fun turnOff() {
        val saved = overview.value?.settings ?: return
        persist(saved.copy(enabled = false), edited = false)
    }

    private fun saveForm() {
        val form = local.value.form ?: return
        if (local.value.saving) return
        val perPoint = read(form.amountPerPoint)
        val value = read(form.pointValue)
        val perPointError = errorOf(perPoint, form.enabled)
        val valueError = errorOf(value, form.enabled)
        if (perPointError != null || valueError != null) {
            editForm { it.copy(amountPerPointError = perPointError, pointValueError = valueError) }
            return
        }
        val program = LoyaltySettings(form.enabled, valueOf(perPoint), valueOf(value))
        persist(program, edited = true)
    }

    private fun persist(program: LoyaltySettings, edited: Boolean) {
        local.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            when (val result = save(program)) {
                SaveLoyaltyProgramResult.Saved -> local.update {
                    it.copy(
                        saving = false,
                        edited = false,
                        savedMessage = true,
                        form = formOf(program),
                    )
                }

                is SaveLoyaltyProgramResult.Invalid -> local.update { shown ->
                    shown.copy(
                        saving = false,
                        edited = edited || shown.edited,
                        form = shown.form?.let { withIssues(it, result.issues) },
                    )
                }

                is SaveLoyaltyProgramResult.StorageFailed -> local.update {
                    it.copy(
                        saving = false,
                        saveFailed = true,
                        outOfSpace = result.failure.isOutOfSpace,
                    )
                }
            }
        }
    }

    private fun editForm(change: (LoyaltyForm) -> LoyaltyForm) = local.update { shown ->
        val form = shown.form ?: return@update shown
        shown.copy(form = change(form), edited = true, saveFailed = false)
    }

    private fun stateOf(
        current: LoyaltyProgramOverview?,
        shown: Local,
        failed: Boolean,
    ): LoyaltyProgramUiState {
        if (failed) return LoyaltyProgramUiState.ReadFailed
        val form = shown.form
        if (current == null || form == null) return LoyaltyProgramUiState.Loading
        val saved = current.settings
        val perPoint = read(form.amountPerPoint)
        val value = read(form.pointValue)
        return LoyaltyProgramUiState.Ready(
            form = form,
            savedEnabled = saved.enabled,
            valuesChanged = saved.enabled &&
                (valueOf(perPoint) != saved.amountPerPoint || valueOf(value) != saved.pointValue),
            example = exampleOf(valueOf(perPoint), valueOf(value)),
            points = current.points,
            hasCustomers = current.hasCustomers,
            saving = shown.saving,
            saveFailed = shown.saveFailed,
            outOfSpace = shown.outOfSpace,
            confirmTurnOff = shown.confirmTurnOff,
            savedMessage = shown.savedMessage,
        )
    }

    private fun formOf(settings: LoyaltySettings): LoyaltyForm = LoyaltyForm(
        enabled = settings.enabled,
        amountPerPoint = settings.amountPerPoint?.let(::text).orEmpty(),
        pointValue = settings.pointValue?.let(::text).orEmpty(),
    )

    private fun text(amount: Money): String =
        plainNumberText(amount.minor, numbers.currencyDecimals, numbers.symbols)

    private fun read(text: String): NumberInput<Money> =
        NumberInputParser.money(text, numbers.symbols, numbers.currencyDecimals, Limit.UNIT_MONEY)

    /** El monto leído si es mayor que cero; un campo vacío o en cero no guarda nada. */
    private fun valueOf(input: NumberInput<Money>): Money? =
        (input as? NumberInput.Value)?.value?.takeIf { it > Money.ZERO }

    /** Apagado, un campo vacío vale: los montos solo son obligatorios para activar. */
    private fun errorOf(input: NumberInput<Money>, required: Boolean): LoyaltyAmountError? =
        when (input) {
            is NumberInput.Value ->
                LoyaltyAmountError.REQUIRED.takeIf { input.value <= Money.ZERO && required }

            NumberInput.Blank -> LoyaltyAmountError.REQUIRED.takeIf { required }

            NumberInput.Malformed -> LoyaltyAmountError.NOT_A_NUMBER

            is NumberInput.TooManyDecimals -> LoyaltyAmountError.DECIMALS

            is NumberInput.TooLarge -> LoyaltyAmountError.TOO_HIGH
        }

    private fun withIssues(form: LoyaltyForm, issues: Set<LoyaltyProgramIssue>): LoyaltyForm {
        fun has(vararg wanted: LoyaltyProgramIssue) = wanted.any { it in issues }
        return form.copy(
            amountPerPointError = when {
                has(LoyaltyProgramIssue.AMOUNT_PER_POINT_REQUIRED) -> LoyaltyAmountError.REQUIRED

                has(
                    LoyaltyProgramIssue.AMOUNT_PER_POINT_OUT_OF_RANGE,
                ) -> LoyaltyAmountError.TOO_HIGH

                else -> form.amountPerPointError
            },
            pointValueError = when {
                has(LoyaltyProgramIssue.POINT_VALUE_REQUIRED) -> LoyaltyAmountError.REQUIRED
                has(LoyaltyProgramIssue.POINT_VALUE_OUT_OF_RANGE) -> LoyaltyAmountError.TOO_HIGH
                else -> form.pointValueError
            },
        )
    }

    /**
     * Una venta de diez veces el monto por punto gana diez puntos, y cien puntos descuentan cien veces
     * el valor del punto. Con cifras que no caben, no hay ejemplo.
     */
    private fun exampleOf(perPoint: Money?, value: Money?): LoyaltyExample? {
        if (perPoint == null || value == null) return null
        val sale = LoyaltyProgramRules.discountFor(EXAMPLE_EARNED, perPoint)
        val discount = LoyaltyProgramRules.discountFor(EXAMPLE_REDEEMED, value)
        if (sale !is Checked.Value || discount !is Checked.Value) return null
        return LoyaltyExample(
            saleAmount = sale.value,
            earnedPoints = LoyaltyProgramRules.pointsEarned(sale.value, perPoint),
            redeemedPoints = EXAMPLE_REDEEMED,
            discount = discount.value,
        )
    }

    /** Lo que vive en la pantalla y no en la base. */
    private data class Local(
        val form: LoyaltyForm? = null,
        val edited: Boolean = false,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
        val outOfSpace: Boolean = false,
        val confirmTurnOff: Boolean = false,
        val savedMessage: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val EXAMPLE_EARNED = 10L
        const val EXAMPLE_REDEEMED = 100L
    }
}
