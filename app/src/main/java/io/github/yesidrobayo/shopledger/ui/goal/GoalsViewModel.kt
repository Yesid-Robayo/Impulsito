package io.github.yesidrobayo.shopledger.ui.goal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.report.RevenueLimitRules
import io.github.yesidrobayo.shopledger.domain.usecase.report.MonthGoalView
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveGoalsResult
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveLimitResult
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth

/**
 * `Metas y límites`: las metas de un mes y su avance, de nuevo con cada venta, gasto o anulación.
 *
 * Abre en el mes en curso. Recorre los meses sin límite hacia atrás y hasta un año hacia adelante,
 * para dejar definidos los meses que vienen. La hoja vive aquí hasta guardarla, y una falla nunca
 * borra lo escrito. Las metas son de la persona: nada se propone ni se calcula solo.
 *
 * @param today hoy en la zona del negocio, o `null` si este teléfono no conoce esa zona.
 * @param observe las metas de un mes con su avance.
 * @param save guarda las metas de un mes; las dos vacías las quitan.
 * @param limit el límite de ingresos y lo acumulado en la ventana que termina en un mes.
 * @param saveLimit guarda el límite que escribió la persona, o lo quita con `null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModel(
    today: Flow<BusinessDate?>,
    private val observe: (YearMonth) -> Flow<MonthGoalView>,
    private val save: suspend (YearMonth, Money?, Money?) -> SaveGoalsResult,
    private val numbers: GoalNumbers,
    private val limit: (YearMonth) -> Flow<RevenueLimitView?> = { flowOf(null) },
    private val saveLimit: suspend (RevenueLimit?) -> SaveLimitResult = { SaveLimitResult.Saved },
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val local = MutableStateFlow(Local())

    /** El mes elegido, o `null` para el mes en curso, que sigue a hoy si cambia la fecha. */
    private val chosen = MutableStateFlow<YearMonth?>(null)

    /** Hoy, guardado para mover el mes sin esperar otra lectura. */
    private var lastToday: BusinessDate? = null

    private val read: Flow<Read> = attempts.flatMapLatest { attempt ->
        combine(today, chosen) { date, month -> date to month }
            .distinctUntilChanged()
            .flatMapLatest { (date, month) ->
                lastToday = date
                if (date == null) {
                    flowOf<Read>(Read.UnknownZone)
                } else {
                    val shown = month ?: date.date.yearMonth
                    // El límite siempre suma hasta el mes en curso, sin importar el mes elegido.
                    combine(observe(shown), limit(date.date.yearMonth)) { goals, written ->
                        Read.Ready(date, shown, goals, written)
                    }
                }
            }
            // Al reintentar, la pantalla deja el error mientras vuelve a leer.
            .onStart { if (attempt > 0) emit(Read.Loading) }
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(Read.Failed) }
    }

    val uiState: StateFlow<GoalsUiState> = combine(read, local) { current, shown ->
        when (current) {
            Read.Loading -> GoalsUiState.Loading

            Read.Failed -> GoalsUiState.ReadFailed

            Read.UnknownZone -> GoalsUiState.UnknownZone

            is Read.Ready -> GoalsUiState.Ready(
                month = current.month,
                today = current.today,
                canGoNext = current.month < lastMonthOf(current.today),
                goals = current.goals,
                sheet = shown.sheet,
                message = shown.message,
                limit = current.limit,
                limitSheet = shown.limitSheet,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), GoalsUiState.Loading)

    fun onAction(action: GoalsAction) {
        when (action) {
            // Volver y elegir la zona los resuelve la navegación.
            GoalsAction.Back, GoalsAction.ChooseZone -> Unit

            GoalsAction.Retry -> attempts.value += 1

            GoalsAction.PreviousMonth -> move(forward = false)

            GoalsAction.NextMonth -> move(forward = true)

            GoalsAction.StartGoals -> startGoals()

            is GoalsAction.SalesText -> editSheet {
                it.copy(salesText = action.text, salesProblem = null, saveFailed = null)
            }

            is GoalsAction.ProfitText -> editSheet {
                it.copy(profitText = action.text, profitProblem = null, saveFailed = null)
            }

            GoalsAction.SaveGoals -> saveGoals()

            GoalsAction.DismissSheet -> local.update { it.copy(sheet = null, limitSheet = null) }

            GoalsAction.MessageShown -> local.update { it.copy(message = null) }

            GoalsAction.StartLimit -> startLimit()

            is GoalsAction.LimitAmountText -> editLimitSheet {
                it.copy(amountText = action.text, amountProblem = null, saveFailed = null)
            }

            is GoalsAction.LimitMonthsText -> editLimitSheet {
                it.copy(monthsText = action.text, monthsInvalid = false, saveFailed = null)
            }

            GoalsAction.SaveLimit -> saveLimitSheet()

            GoalsAction.RemoveLimit -> persistLimit(null)
        }
    }

    /**
     * Abre la hoja del límite con el que ya hay, o vacía con 12 meses: lo más común es seguir un
     * año.
     */
    private fun startLimit() {
        val ready = uiState.value as? GoalsUiState.Ready ?: return
        val saved = ready.limit?.limit
        local.update {
            it.copy(
                limitSheet = LimitSheet(
                    editing = saved != null,
                    amountText = saved?.amount?.let(::text).orEmpty(),
                    monthsText = (saved?.months ?: DEFAULT_LIMIT_MONTHS).toString(),
                ),
            )
        }
    }

    private fun saveLimitSheet() {
        val sheet = local.value.limitSheet ?: return
        if (sheet.saving) return
        val amount = readGoal(sheet.amountText)
        val months = sheet.monthsText.trim().toIntOrNull()
            ?.takeIf { RevenueLimitRules.isValidMonths(it) }
        val amountProblem = when (amount) {
            GoalRead.Empty -> GoalFieldProblem.NOT_POSITIVE
            is GoalRead.Invalid -> amount.problem
            is GoalRead.Valid -> null
        }
        if (amountProblem != null || months == null) {
            editLimitSheet {
                it.copy(amountProblem = amountProblem, monthsInvalid = months == null)
            }
            return
        }
        persistLimit(RevenueLimit(requireNotNull(amount.valueOrNull()), months))
    }

    /** Guarda [written], o quita el límite con `null`, desde la hoja abierta. */
    private fun persistLimit(written: RevenueLimit?) {
        val sheet = local.value.limitSheet ?: return
        if (sheet.saving) return
        editLimitSheet { it.copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            val result = saveLimit(written)
            local.update { shown ->
                val open = shown.limitSheet ?: return@update shown
                when (result) {
                    SaveLimitResult.Saved ->
                        shown.copy(limitSheet = null, message = GoalMessage.LimitSaved)

                    SaveLimitResult.Removed ->
                        shown.copy(limitSheet = null, message = GoalMessage.LimitRemoved)

                    // La hoja ya lee los mismos topes: si llega, cambió algo por debajo.
                    is SaveLimitResult.Invalid -> shown.copy(
                        limitSheet = open.copy(
                            saving = false,
                            amountProblem =
                                GoalFieldProblem.NOT_POSITIVE.takeIf { result.amount },
                            monthsInvalid = result.months,
                        ),
                    )

                    is SaveLimitResult.Failed -> shown.copy(
                        limitSheet = open.copy(
                            saving = false,
                            saveFailed = GoalSaveFailure(result.failure.isOutOfSpace),
                        ),
                    )
                }
            }
        }
    }

    private fun editLimitSheet(change: (LimitSheet) -> LimitSheet) {
        local.update { shown ->
            shown.limitSheet?.let { shown.copy(limitSheet = change(it)) } ?: shown
        }
    }

    /** Pasa al mes anterior o al siguiente; hacia adelante, solo hasta un año después de hoy. */
    private fun move(forward: Boolean) {
        val today = lastToday ?: return
        val current = (uiState.value as? GoalsUiState.Ready)?.month ?: return
        val next = if (forward) current.plusMonth() else current.minusMonth()
        if (forward && next > lastMonthOf(today)) return
        chosen.value = if (next == today.date.yearMonth) null else next
    }

    /** Abre la hoja del mes que se ve, con sus metas si ya tiene. */
    private fun startGoals() {
        val ready = uiState.value as? GoalsUiState.Ready ?: return
        val saved = ready.goals.goals
        local.update {
            it.copy(
                sheet = GoalSheet(
                    month = ready.month,
                    editing = saved != null,
                    salesText = saved?.sales?.let(::text).orEmpty(),
                    profitText = saved?.profit?.let(::text).orEmpty(),
                ),
            )
        }
    }

    private fun saveGoals() {
        val sheet = local.value.sheet ?: return
        if (sheet.saving) return
        val sales = readGoal(sheet.salesText)
        val profit = readGoal(sheet.profitText)
        if (sales is GoalRead.Invalid || profit is GoalRead.Invalid) {
            editSheet {
                it.copy(
                    salesProblem = (sales as? GoalRead.Invalid)?.problem,
                    profitProblem = (profit as? GoalRead.Invalid)?.problem,
                )
            }
            return
        }
        editSheet { it.copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            val result = save(sheet.month, sales.valueOrNull(), profit.valueOrNull())
            local.update { shown ->
                val open = shown.sheet ?: return@update shown
                when (result) {
                    SaveGoalsResult.Saved -> shown.copy(sheet = null, message = GoalMessage.Saved)

                    SaveGoalsResult.Removed ->
                        shown.copy(sheet = null, message = GoalMessage.Removed)

                    // La hoja ya lee los mismos topes: si llega, cambió algo por debajo.
                    is SaveGoalsResult.Invalid -> shown.copy(
                        sheet = open.copy(
                            saving = false,
                            salesProblem = GoalFieldProblem.NOT_POSITIVE.takeIf { result.sales },
                            profitProblem = GoalFieldProblem.NOT_POSITIVE.takeIf { result.profit },
                        ),
                    )

                    is SaveGoalsResult.Failed -> shown.copy(
                        sheet = open.copy(
                            saving = false,
                            saveFailed = GoalSaveFailure(result.failure.isOutOfSpace),
                        ),
                    )
                }
            }
        }
    }

    private fun editSheet(change: (GoalSheet) -> GoalSheet) {
        local.update { shown -> shown.sheet?.let { shown.copy(sheet = change(it)) } ?: shown }
    }

    private fun text(amount: Money): String =
        plainNumberText(amount.minor, numbers.currencyDecimals, numbers.symbols)

    /** Un campo vacío no es error: esa meta queda sin valor. */
    private fun readGoal(text: String): GoalRead = when (
        val read = NumberInputParser.money(
            text,
            numbers.symbols,
            numbers.currencyDecimals,
            Limit.STOCK_VALUE,
        )
    ) {
        NumberInput.Blank -> GoalRead.Empty

        is NumberInput.Value -> if (read.value > Money.ZERO) {
            GoalRead.Valid(read.value)
        } else {
            GoalRead.Invalid(GoalFieldProblem.NOT_POSITIVE)
        }

        NumberInput.Malformed, is NumberInput.TooManyDecimals ->
            GoalRead.Invalid(GoalFieldProblem.NOT_A_NUMBER)

        is NumberInput.TooLarge -> GoalRead.Invalid(GoalFieldProblem.TOO_HIGH)
    }

    private sealed interface GoalRead {
        data object Empty : GoalRead

        data class Valid(val amount: Money) : GoalRead

        data class Invalid(val problem: GoalFieldProblem) : GoalRead

        fun valueOrNull(): Money? = (this as? Valid)?.amount
    }

    private sealed interface Read {
        data object Loading : Read

        data object Failed : Read

        data object UnknownZone : Read

        data class Ready(
            val today: BusinessDate,
            val month: YearMonth,
            val goals: MonthGoalView,
            val limit: RevenueLimitView?,
        ) : Read
    }

    private data class Local(
        val sheet: GoalSheet? = null,
        val message: GoalMessage? = null,
        val limitSheet: LimitSheet? = null,
    )

    private companion object {
        /** Cuántos meses después del mes en curso se pueden dejar definidos. Valor de diseño. */
        const val MONTHS_AHEAD = 12

        /** Los meses con que abre la hoja de un límite nuevo: un año. Valor de diseño. */
        const val DEFAULT_LIMIT_MONTHS = 12

        /** Tras girar la pantalla, las lecturas siguen vivas este tiempo y no se repiten. */
        const val STOP_AFTER_MS = 5_000L

        /** El último mes al que se puede llegar desde [today]. */
        fun lastMonthOf(today: BusinessDate): YearMonth {
            var month = today.date.yearMonth
            repeat(MONTHS_AHEAD) { month = month.plusMonth() }
            return month
        }
    }
}
