package io.github.yesidrobayo.shopledger.ui.quote

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentTotals
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversion
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que el detalle de una cotización lee y escribe. */
interface QuoteDetailOperations {
    /** La cotización con sus líneas, o `null` si no existe. */
    fun quote(quoteId: Long): Flow<QuoteView?>

    /** Las unidades, por id, para decir cada cantidad con la suya. */
    fun units(): Flow<Map<Long, MeasureUnit>>

    /** Hoy en la zona del negocio, o `null` si el teléfono no la conoce. */
    fun today(): Flow<BusinessDate?>

    suspend fun conversion(quoteId: Long): QuoteConversionResult

    suspend fun cancel(quoteId: Long): CancelQuoteResult
}

/** Una línea como se muestra: lo congelado al cotizar, sin costo. */
@Immutable
data class QuoteLineUi(
    val name: String,
    val quantity: QuantityMilli,
    val unit: MeasureUnit?,
    val unitPrice: Money,
    val discount: Money,
    val total: Money,
)

/** La cotización abierta, lista para mostrar. [expired] es abierta con la vigencia pasada. */
@Immutable
data class QuoteDetail(
    val id: Long,
    val status: QuoteStatus,
    val customerName: String?,
    val businessDate: BusinessDate,
    val validUntil: BusinessDate?,
    val expired: Boolean,
    val totals: DocumentTotals,
    val note: String?,
    val lines: List<QuoteLineUi>,
    val convertedSaleId: Long?,
)

sealed interface QuoteDetailContent {
    data object Loading : QuoteDetailContent

    /** Ya no existe. */
    data object Missing : QuoteDetailContent

    data object Failed : QuoteDetailContent

    data class Ready(val quote: QuoteDetail) : QuoteDetailContent
}

/** Lo que se pregunta antes de seguir. Uno a la vez. */
sealed interface QuoteDetailDialog {
    data object ConfirmCancel : QuoteDetailDialog

    /** Venció el [date]: se puede convertir igual. */
    data class Expired(val date: BusinessDate) : QuoteDetailDialog

    /** Cambió el precio de [count] productos: se elige con cuál se vende. */
    data class PriceChanged(val count: Int) : QuoteDetailDialog
}

/** Un aviso breve, que se muestra una vez. */
enum class QuoteDetailNotice {
    CONVERT_FAILED,
    CANCEL_FAILED,
    CANCELLED,
}

/** Abrir la venta con las líneas de la cotización: lo resuelve la ruta, una vez. */
@Immutable
data class QuoteToSell(val quoteId: Long, val useCurrentPrices: Boolean)

@Immutable
data class QuoteDetailUiState(
    val content: QuoteDetailContent = QuoteDetailContent.Loading,
    val dialog: QuoteDetailDialog? = null,
    val working: Boolean = false,
    val notice: QuoteDetailNotice? = null,
    val toSell: QuoteToSell? = null,
)

sealed interface QuoteDetailAction {
    data object Convert : QuoteDetailAction

    /** Tras el aviso de vencida, sigue con la conversión. */
    data object ConfirmExpired : QuoteDetailAction

    data class ChoosePrices(val useCurrentPrices: Boolean) : QuoteDetailAction

    data object AskCancel : QuoteDetailAction

    data object ConfirmCancel : QuoteDetailAction

    data object DismissDialog : QuoteDetailAction

    data object Retry : QuoteDetailAction

    data object NoticeShown : QuoteDetailAction

    data object SellShown : QuoteDetailAction

    /** Navegación y compartir: los resuelve la ruta. */
    data object Back : QuoteDetailAction

    data object Edit : QuoteDetailAction

    data object Share : QuoteDetailAction

    data object ViewSale : QuoteDetailAction
}

/**
 * El detalle de una cotización. Convertir lee primero qué cambió: si venció lo avisa, y si cambió
 * algún precio pregunta con cuál se vende. Después abre la venta, que es la que la deja convertida
 * al registrarse: salir sin vender la deja abierta. Cancelar pregunta antes, y la deja en el
 * historial sin acciones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuoteDetailViewModel(
    private val quoteId: Long,
    private val operations: QuoteDetailOperations,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val overlay = MutableStateFlow(QuoteDetailUiState())
    private var pending: QuoteConversion? = null

    // Reintentar vuelve a leer desde cero: una lectura que falló ya terminó su flujo.
    private val content: Flow<QuoteDetailContent> = attempts.flatMapLatest {
        combine(
            operations.quote(quoteId),
            operations.units().catch { emit(emptyMap()) },
            operations.today().catch { emit(null) },
        ) { view, units, today ->
            view?.let { QuoteDetailContent.Ready(detailOf(it, units, today)) }
                ?: QuoteDetailContent.Missing
        }.catch { emit(QuoteDetailContent.Failed) }
    }

    val uiState: StateFlow<QuoteDetailUiState> = combine(content, overlay) { read, shown ->
        shown.copy(content = read)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        QuoteDetailUiState(),
    )

    fun onAction(action: QuoteDetailAction) {
        when (action) {
            QuoteDetailAction.Convert -> convert()

            QuoteDetailAction.ConfirmExpired -> {
                overlay.update { it.copy(dialog = null) }
                pending?.let(::askPrices)
            }

            is QuoteDetailAction.ChoosePrices -> {
                overlay.update { it.copy(dialog = null) }
                sell(action.useCurrentPrices)
            }

            QuoteDetailAction.AskCancel ->
                overlay.update { it.copy(dialog = QuoteDetailDialog.ConfirmCancel) }

            QuoteDetailAction.ConfirmCancel -> cancel()

            QuoteDetailAction.DismissDialog -> {
                pending = null
                overlay.update { it.copy(dialog = null) }
            }

            QuoteDetailAction.Retry -> attempts.update { it + 1 }

            QuoteDetailAction.NoticeShown -> overlay.update { it.copy(notice = null) }

            QuoteDetailAction.SellShown -> overlay.update { it.copy(toSell = null) }

            QuoteDetailAction.Back,
            QuoteDetailAction.Edit,
            QuoteDetailAction.Share,
            QuoteDetailAction.ViewSale,
            -> Unit
        }
    }

    private fun convert() {
        if (overlay.value.working) return
        overlay.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = try {
                operations.conversion(quoteId)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: StorageFailureException) {
                null
            }
            overlay.update { it.copy(working = false) }
            when (result) {
                is QuoteConversionResult.Ready -> {
                    val conversion = result.conversion
                    pending = conversion
                    val expired = conversion.expiredOn
                    if (expired != null) {
                        overlay.update { it.copy(dialog = QuoteDetailDialog.Expired(expired)) }
                    } else {
                        askPrices(conversion)
                    }
                }

                // Ya convertida, cancelada o sin nada que vender: el detalle se actualiza solo, y
                // la persona ve por qué ya no se convierte.
                null,
                QuoteConversionResult.NotFound,
                is QuoteConversionResult.NotOpen,
                QuoteConversionResult.NothingToConvert,
                is QuoteConversionResult.UnknownTimeZone,
                -> overlay.update { it.copy(notice = QuoteDetailNotice.CONVERT_FAILED) }
            }
        }
    }

    private fun askPrices(conversion: QuoteConversion) {
        val changed = conversion.changedPrices
        if (changed > 0) {
            overlay.update { it.copy(dialog = QuoteDetailDialog.PriceChanged(changed)) }
        } else {
            sell(useCurrentPrices = false)
        }
    }

    private fun sell(useCurrentPrices: Boolean) {
        pending = null
        overlay.update { it.copy(toSell = QuoteToSell(quoteId, useCurrentPrices)) }
    }

    private fun cancel() {
        overlay.update { it.copy(dialog = null, working = true) }
        viewModelScope.launch {
            val result = try {
                operations.cancel(quoteId)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: StorageFailureException) {
                null
            }
            val notice = when (result) {
                CancelQuoteResult.Cancelled -> QuoteDetailNotice.CANCELLED

                null,
                CancelQuoteResult.NotOpen,
                is CancelQuoteResult.StorageFailed,
                -> QuoteDetailNotice.CANCEL_FAILED
            }
            overlay.update { it.copy(working = false, notice = notice) }
        }
    }

    private fun detailOf(
        view: QuoteView,
        units: Map<Long, MeasureUnit>,
        today: BusinessDate?,
    ): QuoteDetail {
        val quote = view.quote
        val validUntil = quote.validUntil
        return QuoteDetail(
            id = quote.id,
            status = quote.status,
            customerName = view.customerName,
            businessDate = quote.businessDate,
            validUntil = validUntil,
            expired = quote.status == QuoteStatus.OPEN && validUntil != null && today != null &&
                validUntil < today,
            totals = quote.totals,
            note = quote.note,
            lines = view.lines.map { line ->
                val content = line.content
                QuoteLineUi(
                    name = content.productNameSnapshot,
                    quantity = content.enteredQuantity,
                    unit = units[content.unitId],
                    unitPrice = content.unitPrice,
                    discount = content.lineDiscount,
                    total = content.lineTotal,
                )
            },
            convertedSaleId = quote.convertedSaleId,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
