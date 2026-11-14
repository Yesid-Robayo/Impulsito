package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetail
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturn
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que muestra el detalle de una compra. */
sealed interface PurchaseDetailUiState {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : PurchaseDetailUiState

    /** La compra no existe. */
    data object NotFound : PurchaseDetailUiState

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : PurchaseDetailUiState

    /**
     * La compra, con la hoja de anular si está abierta, en qué va la anulación y si acaba de
     * anularse, para avisarlo una vez. Con [confirmCorrect] pregunta antes de anular y corregir, y
     * [correctedId] es la compra recién anulada para corregir: la navegación abre la compra nueva.
     */
    @Immutable
    data class Ready(
        val detail: PurchaseDetail,
        val sheet: PurchaseVoidSheet? = null,
        val status: PurchaseVoidStatus = PurchaseVoidStatus.IDLE,
        val justVoided: Boolean = false,
        val confirmCorrect: Boolean = false,
        val correctedId: Long? = null,
        /** Lo que falta pagar de una compra a plazo, o `null` si no fue a plazo o no se sabe. */
        val creditBalance: Money? = null,
        /** Las devoluciones al proveedor de esta compra, activas y anuladas. */
        val returns: List<PurchaseReturn> = emptyList(),
    ) : PurchaseDetailUiState {
        /** Solo una compra activa se anula, o se devuelve. */
        val canVoid: Boolean get() = detail.purchase.status == DocumentStatus.ACTIVE

        /**
         * Una compra con una devolución activa no se anula ni se corrige: se devuelve el resto, o se
         * anula primero la devolución.
         */
        val canVoidPurchase: Boolean
            get() = canVoid && returns.none { it.status == DocumentStatus.ACTIVE }
    }
}

/** La hoja de anular: el motivo y la nota que eligió la persona. */
data class PurchaseVoidSheet(
    val reason: VoidReason = VoidReason.ENTRY_ERROR,
    val note: String = "",
) {
    val noteTooLong: Boolean get() = note.length > MAX_VOID_NOTE_LENGTH

    companion object {
        /** Largo máximo de la nota de una anulación. */
        const val MAX_VOID_NOTE_LENGTH = 200

        /** Los motivos que la persona elige para una compra. */
        val REASONS: List<VoidReason> =
            listOf(VoidReason.ENTRY_ERROR, VoidReason.SUPPLIER_RETURN, VoidReason.OTHER)
    }
}

/** En qué va la anulación de una compra. */
enum class PurchaseVoidStatus {
    IDLE,

    /** Anulando: el botón muestra su indicador e ignora más toques. */
    WORKING,

    /** No se pudo anular. La compra sigue activa, y se puede reintentar. */
    FAILED,
}

/** Lo que la persona hace en el detalle de una compra. */
sealed interface PurchaseDetailAction {
    data object OpenVoid : PurchaseDetailAction

    data class ReasonChange(val reason: VoidReason) : PurchaseDetailAction

    data class NoteChange(val note: String) : PurchaseDetailAction

    data object ConfirmVoid : PurchaseDetailAction

    /** Pedir confirmación de anular y corregir. */
    data object StartCorrect : PurchaseDetailAction

    data object CloseCorrect : PurchaseDetailAction

    /** Anular la compra y abrir una nueva con sus datos. */
    data object ConfirmCorrect : PurchaseDetailAction

    /** La compra nueva ya se abrió. */
    data object CorrectionOpened : PurchaseDetailAction

    data object CloseSheet : PurchaseDetailAction

    /** El aviso de compra anulada ya se mostró. */
    data object VoidShown : PurchaseDetailAction

    data object Retry : PurchaseDetailAction

    data object Back : PurchaseDetailAction

    /** `Registrar pago` de una compra a plazo: abre el libro del proveedor. */
    data class OpenPayable(val supplierId: Long) : PurchaseDetailAction

    /** `Devolver al proveedor`: abre la devolución con esta compra de origen. */
    data object ReturnToSupplier : PurchaseDetailAction

    /** Abre una devolución de esta compra: lo resuelve la navegación. */
    data class OpenReturn(val purchaseReturnId: Long) : PurchaseDetailAction
}

/**
 * El detalle de una compra y su anulación. La compra no se edita: solo se anula, desde una hoja con
 * el motivo, una nota opcional y lo que pasará con el stock, también si quedaría negativo.
 *
 * @param detailSource lee el detalle, y vuelve a emitir con cada cambio; se pide de nuevo al
 *   reintentar.
 * @param voidPurchase anula la compra en una sola transacción.
 * @param purchaseId la compra del detalle, que se corrige con `Anular y corregir`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PurchaseDetailViewModel(
    private val detailSource: () -> Flow<PurchaseDetail?>,
    private val voidPurchase: suspend (VoidReason, String?) -> VoidPurchaseResult,
    private val purchaseId: Long = 0L,
    private val purchaseBalance: (supplierId: Long) -> Flow<Money?> = { flowOf(null) },
    private val returnsSource: () -> Flow<List<PurchaseReturn>> = { flowOf(emptyList()) },
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val overlay = MutableStateFlow(Overlay())

    private val loaded: Flow<Loaded> = attempts.flatMapLatest {
        detailSource()
            .map<PurchaseDetail?, Loaded> { Loaded.Read(it) }
            .catch { emit(Loaded.Failed) }
    }

    /** Lo que falta pagar, si fue a plazo: sigue los pagos que entran al libro del proveedor. */
    private val credit: Flow<Money?> = loaded
        .map { read ->
            val content = (read as? Loaded.Read)?.detail?.purchase?.content
            content?.supplierId?.takeIf { content.amountPaid < content.total }
        }
        .distinctUntilChanged()
        .flatMapLatest { supplierId ->
            if (supplierId ==
                null
            ) {
                flowOf(null)
            } else {
                purchaseBalance(supplierId).catch { emit(null) }
            }
        }
        .onStart { emit(null) }

    /** Las devoluciones de la compra: si falla su lectura, el detalle sigue sin ellas. */
    private val returns: Flow<List<PurchaseReturn>> = attempts.flatMapLatest {
        returnsSource().catch { emit(emptyList()) }
    }.onStart { emit(emptyList()) }

    val uiState: StateFlow<PurchaseDetailUiState> = combine(loaded, overlay, credit, returns) {
            read,
            shown,
            balance,
            purchaseReturns,
        ->
        when (read) {
            Loaded.Failed -> PurchaseDetailUiState.ReadError

            is Loaded.Read -> read.detail?.let {
                PurchaseDetailUiState.Ready(
                    it,
                    shown.sheet,
                    shown.status,
                    shown.justVoided,
                    shown.confirmCorrect,
                    shown.correctedId,
                    balance,
                    purchaseReturns,
                )
            } ?: PurchaseDetailUiState.NotFound
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_MILLIS),
        PurchaseDetailUiState.Loading,
    )

    fun onAction(action: PurchaseDetailAction) {
        when (action) {
            PurchaseDetailAction.OpenVoid -> overlay.update {
                it.copy(sheet = PurchaseVoidSheet(), status = PurchaseVoidStatus.IDLE)
            }

            is PurchaseDetailAction.ReasonChange -> editSheet { it.copy(reason = action.reason) }

            is PurchaseDetailAction.NoteChange -> editSheet { it.copy(note = action.note) }

            PurchaseDetailAction.ConfirmVoid -> confirm()

            PurchaseDetailAction.StartCorrect -> overlay.update {
                it.copy(confirmCorrect = true, status = PurchaseVoidStatus.IDLE)
            }

            PurchaseDetailAction.CloseCorrect -> overlay.update { it.copy(confirmCorrect = false) }

            PurchaseDetailAction.ConfirmCorrect -> correct()

            PurchaseDetailAction.CorrectionOpened -> overlay.update { it.copy(correctedId = null) }

            PurchaseDetailAction.CloseSheet -> overlay.update { it.copy(sheet = null) }

            PurchaseDetailAction.VoidShown -> overlay.update { it.copy(justVoided = false) }

            PurchaseDetailAction.Retry -> attempts.update { it + 1 }

            // Volver lo resuelve la navegación.
            PurchaseDetailAction.Back,
            is PurchaseDetailAction.OpenPayable,
            PurchaseDetailAction.ReturnToSupplier,
            is PurchaseDetailAction.OpenReturn,
            -> Unit
        }
    }

    private fun editSheet(change: (PurchaseVoidSheet) -> PurchaseVoidSheet) = overlay.update {
        it.copy(sheet = it.sheet?.let(change))
    }

    private fun confirm() {
        val current = overlay.value
        val sheet = current.sheet ?: return
        if (current.status == PurchaseVoidStatus.WORKING || sheet.noteTooLong) return
        overlay.update { it.copy(status = PurchaseVoidStatus.WORKING) }
        viewModelScope.launch {
            val result = voidPurchase(sheet.reason, sheet.note.trim().ifEmpty { null })
            overlay.update {
                when (result) {
                    // Ya anulada en otra pantalla: el detalle lo muestra, sin error.
                    VoidPurchaseResult.Voided, VoidPurchaseResult.AlreadyVoided ->
                        Overlay(justVoided = result == VoidPurchaseResult.Voided)

                    VoidPurchaseResult.PurchaseNotFound,
                    VoidPurchaseResult.HasActiveReturns,
                    VoidPurchaseResult.ReasonNotAllowed,
                    VoidPurchaseResult.NoteTooLong,
                    is VoidPurchaseResult.UnknownTimeZone,
                    VoidPurchaseResult.LedgerDamaged,
                    is VoidPurchaseResult.LotAlreadyUsed,
                    is VoidPurchaseResult.StorageFailed,
                    -> it.copy(status = PurchaseVoidStatus.FAILED)
                }
            }
        }
    }

    /**
     * Anula la compra con el motivo de error al registrar y pide abrir la compra nueva con sus
     * datos. Son dos pasos: si la nueva no se registra, la original queda anulada, y se advirtió
     * antes. Si no se pudo anular, la compra sigue activa y el detalle lo dice.
     */
    private fun correct() {
        if (overlay.value.status == PurchaseVoidStatus.WORKING) return
        overlay.update { it.copy(confirmCorrect = false, status = PurchaseVoidStatus.WORKING) }
        viewModelScope.launch {
            val result = voidPurchase(VoidReason.ENTRY_ERROR, null)
            overlay.update {
                when (result) {
                    // Ya anulada en otra pantalla: igual se corrige.
                    VoidPurchaseResult.Voided, VoidPurchaseResult.AlreadyVoided ->
                        Overlay(correctedId = purchaseId)

                    VoidPurchaseResult.PurchaseNotFound,
                    VoidPurchaseResult.HasActiveReturns,
                    VoidPurchaseResult.ReasonNotAllowed,
                    VoidPurchaseResult.NoteTooLong,
                    is VoidPurchaseResult.UnknownTimeZone,
                    VoidPurchaseResult.LedgerDamaged,
                    is VoidPurchaseResult.LotAlreadyUsed,
                    is VoidPurchaseResult.StorageFailed,
                    -> it.copy(status = PurchaseVoidStatus.FAILED)
                }
            }
        }
    }

    private data class Overlay(
        val sheet: PurchaseVoidSheet? = null,
        val status: PurchaseVoidStatus = PurchaseVoidStatus.IDLE,
        val justVoided: Boolean = false,
        val confirmCorrect: Boolean = false,
        val correctedId: Long? = null,
    )

    private sealed interface Loaded {
        data class Read(val detail: PurchaseDetail?) : Loaded

        data object Failed : Loaded
    }

    private companion object {
        const val STOP_MILLIS = 5_000L
    }
}
