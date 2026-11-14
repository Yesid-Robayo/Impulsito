package io.github.yesidrobayo.shopledger.ui.count

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountReview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountSessionView
import io.github.yesidrobayo.shopledger.domain.usecase.stock.FinishCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que una sesión de conteo le pide al dominio, para probarla sin una base. */
interface CountSessionOperations {
    fun session(sessionId: Long): Flow<CountSessionView?>

    fun showsLocations(): Flow<Boolean>

    suspend fun record(sessionId: Long, lineId: Long, counted: QuantityMilli?): RecordCountResult

    suspend fun review(view: CountSessionView): CountReview

    suspend fun apply(sessionId: Long): FinishCountResult

    suspend fun cancel(sessionId: Long): FinishCountResult
}

/**
 * Una sesión de conteo: se cuenta producto por producto, se revisan las diferencias y se aplican.
 *
 * - **Cada conteo se guarda al escribirlo,** en la base: cerrar la app o perder la batería no lo
 *   pierde, y al volver la sesión sigue donde estaba.
 * - **A ciegas no se ve lo esperado** hasta la revisión: quien cuenta no se deja llevar.
 * - **La revisión avisa lo que se movió** después de abrir, para recontarlo antes de aplicar.
 * - **Aplicar o cancelar pide confirmación,** y un error deja la sesión abierta y lo contado intacto.
 */
class CountSessionViewModel(
    private val sessionId: Long,
    private val operations: CountSessionOperations,
    private val quantitySymbols: NumberSymbols,
) : ViewModel() {
    private val state = MutableStateFlow(CountSessionUiState())
    val uiState: StateFlow<CountSessionUiState> = state.asStateFlow()

    private val reads = ScreenReads()
    private var view: CountSessionView? = null

    /** Lo escrito en cada fila, tal cual, mientras no coincide con lo guardado. */
    private val texts = mutableMapOf<Long, String>()
    private val errors = mutableMapOf<Long, CountRowError>()
    private var moved: Set<Long> = emptySet()

    init {
        viewModelScope.launch {
            reads.guard { operations.session(sessionId) }.collect { read ->
                view = read
                if (read == null) {
                    state.update { it.copy(loading = false, missing = true) }
                } else {
                    rebuild()
                }
            }
        }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                if (failed) state.update { it.copy(loading = false, readFailed = true) }
            }
        }
        viewModelScope.launch {
            operations.showsLocations().collect { shows ->
                state.update { it.copy(showsLocation = shows) }
            }
        }
    }

    fun onAction(action: CountSessionAction) {
        when (action) {
            // Volver de la revisión a contar rearma las filas con la búsqueda y el filtro.
            CountSessionAction.Back -> if (state.value.reviewing) {
                state.update { it.copy(reviewing = false) }
                rebuild()
            }

            CountSessionAction.Retry -> {
                state.update { it.copy(loading = true, readFailed = false) }
                reads.retry()
            }

            is CountSessionAction.Query -> {
                state.update { it.copy(query = action.text) }
                rebuild()
            }

            is CountSessionAction.Filter -> {
                state.update { it.copy(filter = action.filter) }
                rebuild()
            }

            is CountSessionAction.Count -> count(action.lineId, action.text)

            is CountSessionAction.Step -> step(action.lineId, if (action.up) 1 else -1)

            is CountSessionAction.Scanned -> scanned(action.productId, action.times)

            // La navegación abre el escáner.
            CountSessionAction.Scan -> Unit

            CountSessionAction.Review -> review()

            // Sin diferencias, cerrar no cambia el stock: no hay nada que confirmar.
            CountSessionAction.AskApply -> if (differences() == 0) {
                finish(CountDialog.APPLY)
            } else {
                state.update { it.copy(dialog = CountDialog.APPLY) }
            }

            CountSessionAction.AskCancel -> state.update { it.copy(dialog = CountDialog.CANCEL) }

            CountSessionAction.Confirm -> state.value.dialog?.let { finish(it) }

            CountSessionAction.DismissDialog -> state.update { it.copy(dialog = null) }

            // La navegación abre los ajustes de la región.
            CountSessionAction.ChooseZone -> Unit

            CountSessionAction.FinishedShown -> state.update { it.copy(finished = null) }
        }
    }

    /** Arma las filas con lo guardado, lo escrito, la búsqueda y el filtro. */
    private fun rebuild() {
        val current = view ?: return
        val all = current.lines.map { item ->
            val counted = item.line.counted
            CountRowUi(
                lineId = item.line.id,
                name = item.product?.details?.name.orEmpty(),
                unit = item.unit,
                expected = item.line.expected,
                counted = counted,
                text = texts[item.line.id]
                    ?: counted?.let {
                        fixedPointInputText(it.milli, MILLI_DECIMALS, quantitySymbols)
                    }
                    ?: "",
                error = errors[item.line.id],
                moved = item.line.productId in moved,
            )
        }
        state.update { ui ->
            val wanted = TextNormalizer.normalize(ui.query.trim())
            val shown = all
                .filter { wanted.isEmpty() || wanted in TextNormalizer.normalize(it.name) }
                .filter {
                    when (ui.filter) {
                        null -> true
                        CountFilter.PENDING -> it.counted == null
                        CountFilter.DONE -> it.counted != null
                    }
                }
            ui.copy(
                loading = false,
                missing = false,
                readFailed = false,
                session = current.session,
                location = current.location,
                categoryName = current.category?.details?.name,
                rows = if (ui.reviewing) all else shown,
                counted = all.count { it.counted != null },
                total = all.size,
                canScan = current.lines.any { it.product?.details?.barcode != null },
            )
        }
    }

    private fun count(lineId: Long, text: String) {
        if (state.value.readOnly) return
        texts[lineId] = text
        errors.remove(lineId)
        val allowsFraction = unitOf(lineId)?.allowsFraction ?: false
        val read = NumberInputParser.quantity(text, quantitySymbols, Limit.LINE_QUANTITY_MILLI)
        val value: QuantityMilli? = when (read) {
            NumberInput.Blank -> null

            is NumberInput.Value -> when {
                read.value.milli < 0L -> return markError(lineId, CountRowError.NEGATIVE)

                !allowsFraction && read.value.milli % MILLI_PER_UNIT != 0L ->
                    return markError(lineId, CountRowError.DECIMALS)

                else -> read.value
            }

            NumberInput.Malformed -> return markError(lineId, CountRowError.NOT_A_NUMBER)

            is NumberInput.TooManyDecimals -> return markError(lineId, CountRowError.DECIMALS)

            is NumberInput.TooLarge -> return markError(lineId, CountRowError.TOO_HIGH)
        }
        rebuild()
        save(lineId, value)
    }

    /**
     * Suma o resta [units] unidades. Sin contar, sumar empieza en 1; nunca baja de cero. Lee lo
     * guardado de la sesión, no de la fila visible: una búsqueda o un chip pueden ocultarla.
     */
    private fun step(lineId: Long, units: Int) {
        if (state.value.readOnly) return
        val counted = view?.lines?.firstOrNull { it.line.id == lineId }?.line?.counted
        val now = counted?.milli ?: 0L
        val next = (now + units * MILLI_PER_UNIT).coerceAtLeast(0L)
        if (counted != null && next == now) return
        texts.remove(lineId)
        errors.remove(lineId)
        save(lineId, QuantityMilli(next))
    }

    /**
     * Lo que leyó el escáner: en unidades enteras suma una por lectura; si la unidad admite
     * decimales, busca el producto para escribir lo pesado. Un código de un producto que no está
     * en este conteo no cambia nada.
     */
    private fun scanned(productId: Long, times: Int) {
        if (state.value.readOnly || state.value.reviewing) return
        val item = view?.lines?.firstOrNull { it.line.productId == productId } ?: return
        if (item.unit?.allowsFraction == true) {
            state.update { it.copy(query = item.product?.details?.name.orEmpty(), filter = null) }
            rebuild()
        } else {
            step(item.line.id, times)
        }
    }

    private fun save(lineId: Long, counted: QuantityMilli?) {
        viewModelScope.launch {
            when (val result = operations.record(sessionId, lineId, counted)) {
                // La línea vuelve con lo guardado: lo escrito ya coincide.
                RecordCountResult.Recorded -> texts.remove(lineId)

                is RecordCountResult.InvalidQuantity -> errors[lineId] = when (result.check) {
                    UnitQuantityCheck.FractionNotAllowed -> CountRowError.DECIMALS
                    UnitQuantityCheck.NotPositive -> CountRowError.NEGATIVE
                    UnitQuantityCheck.Valid -> CountRowError.NOT_A_NUMBER
                }

                RecordCountResult.AboveLimit -> errors[lineId] = CountRowError.TOO_HIGH

                // La sesión terminó en otra pantalla: se vuelve a leer como quedó.
                RecordCountResult.NotFound, RecordCountResult.SessionClosed -> reads.retry()

                is RecordCountResult.StorageFailed -> state.update {
                    it.copy(notice = CountNotice.SaveFailed(result.failure.isOutOfSpace))
                }
            }
            rebuild()
        }
    }

    private fun markError(lineId: Long, error: CountRowError) {
        errors[lineId] = error
        rebuild()
    }

    private fun unitOf(lineId: Long) = view?.lines?.firstOrNull { it.line.id == lineId }?.unit

    private fun review() {
        val current = view ?: return
        state.update { it.copy(working = true, notice = null) }
        viewModelScope.launch {
            val review = operations.review(current)
            moved = review.moved
            state.update {
                it.copy(
                    working = false,
                    reviewing = true,
                    review = CountReviewUi(
                        adjustments = review.adjustments,
                        surplus = review.surplus,
                        shortage = review.shortage,
                        movedCount = current.lines.count { line ->
                            line.line.productId in review.moved
                        },
                    ),
                )
            }
            rebuild()
        }
    }

    /** Cuántas líneas contadas difieren de lo esperado: los ajustes que dejaría aplicar. */
    private fun differences(): Int = view?.lines?.count { item ->
        (item.line.difference ?: QuantityMilli.ZERO).milli != 0L
    } ?: 0

    private fun finish(dialog: CountDialog) {
        if (state.value.working || state.value.readOnly) return
        state.update { it.copy(dialog = null, working = true, notice = null) }
        viewModelScope.launch {
            val result = when (dialog) {
                CountDialog.APPLY -> operations.apply(sessionId)
                CountDialog.CANCEL -> operations.cancel(sessionId)
            }
            state.update { current ->
                when (result) {
                    is FinishCountResult.Applied ->
                        current.copy(finished = CountFinished.Applied(result.adjustments))

                    FinishCountResult.Cancelled -> current.copy(finished = CountFinished.Cancelled)

                    FinishCountResult.UnknownTimeZone ->
                        current.copy(working = false, notice = CountNotice.UnknownZone)

                    FinishCountResult.CurrencyUnconfirmed ->
                        current.copy(working = false, notice = CountNotice.CurrencyUnconfirmed)

                    is FinishCountResult.StorageFailed -> current.copy(
                        working = false,
                        notice = CountNotice.ApplyFailed(result.failure.isOutOfSpace),
                    )

                    // Ya terminó en otra pantalla, o un ajuste pasaría un tope: nada se aplicó.
                    FinishCountResult.NotFound,
                    FinishCountResult.AlreadyFinished,
                    is FinishCountResult.AboveLimit,
                    -> current.copy(working = false, notice = CountNotice.ApplyFailed(false))
                }
            }
        }
    }

    private companion object {
        const val MILLI_PER_UNIT = 1_000L
        const val MILLI_DECIMALS = 3
    }
}
