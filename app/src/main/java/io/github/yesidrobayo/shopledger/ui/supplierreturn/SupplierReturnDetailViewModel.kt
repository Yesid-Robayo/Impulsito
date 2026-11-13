package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnDetail
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que muestra el detalle de una devolución a proveedor. */
sealed interface SupplierReturnDetailUiState {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : SupplierReturnDetailUiState

    /** La devolución no existe. */
    data object NotFound : SupplierReturnDetailUiState

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : SupplierReturnDetailUiState

    /**
     * La devolución, con la confirmación de anular si está abierta, en qué va la anulación y si
     * acaba de anularse, para avisarlo una vez.
     */
    data class Ready(
        val detail: SupplierReturnDetail,
        val confirmVoid: Boolean = false,
        val voiding: Boolean = false,
        val voidFailed: Boolean = false,
        val justVoided: Boolean = false,
    ) : SupplierReturnDetailUiState {
        /** Solo una devolución activa se anula. */
        val canVoid: Boolean get() = detail.purchaseReturn.status == DocumentStatus.ACTIVE
    }
}

/** Lo que la persona hace en el detalle de una devolución. */
sealed interface SupplierReturnDetailAction {
    data object Back : SupplierReturnDetailAction

    data object Retry : SupplierReturnDetailAction

    data object AskVoid : SupplierReturnDetailAction

    data object DismissVoid : SupplierReturnDetailAction

    data object ConfirmVoid : SupplierReturnDetailAction

    /** El aviso de devolución anulada ya se mostró. */
    data object VoidShown : SupplierReturnDetailAction
}

/**
 * El detalle de una devolución a proveedor y su anulación. La devolución no se edita: solo se
 * anula, tras confirmar lo que pasará: la mercancía vuelve, el dinero o el crédito se revierte y el
 * costo se recalcula.
 *
 * @param detailSource lee el detalle, y vuelve a emitir con cada cambio; se pide de nuevo al
 *   reintentar.
 * @param voidReturn anula la devolución en una sola transacción.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierReturnDetailViewModel(
    private val detailSource: () -> Flow<SupplierReturnDetail?>,
    private val voidReturn: suspend () -> VoidSupplierReturnResult,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val overlay = MutableStateFlow(Overlay())

    private val loaded: Flow<Loaded> = attempts.flatMapLatest {
        detailSource()
            .map<SupplierReturnDetail?, Loaded> { Loaded.Read(it) }
            .catch { emit(Loaded.Failed) }
    }

    val uiState: StateFlow<SupplierReturnDetailUiState> =
        combine(loaded, overlay) { read, shown ->
            when (read) {
                Loaded.Failed -> SupplierReturnDetailUiState.ReadError

                is Loaded.Read -> read.detail?.let {
                    SupplierReturnDetailUiState.Ready(
                        detail = it,
                        confirmVoid = shown.confirmVoid,
                        voiding = shown.voiding,
                        voidFailed = shown.voidFailed,
                        justVoided = shown.justVoided,
                    )
                } ?: SupplierReturnDetailUiState.NotFound
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_MILLIS),
            SupplierReturnDetailUiState.Loading,
        )

    fun onAction(action: SupplierReturnDetailAction) {
        when (action) {
            SupplierReturnDetailAction.Retry -> attempts.update { it + 1 }

            SupplierReturnDetailAction.AskVoid -> overlay.update {
                it.copy(confirmVoid = true, voidFailed = false)
            }

            SupplierReturnDetailAction.DismissVoid -> overlay.update {
                it.copy(confirmVoid = false)
            }

            SupplierReturnDetailAction.ConfirmVoid -> confirm()

            SupplierReturnDetailAction.VoidShown -> overlay.update { it.copy(justVoided = false) }

            // La resuelve la navegación.
            SupplierReturnDetailAction.Back -> Unit
        }
    }

    private fun confirm() {
        if (overlay.value.voiding) return
        overlay.update { it.copy(confirmVoid = false, voiding = true, voidFailed = false) }
        viewModelScope.launch {
            val result = voidReturn()
            overlay.update {
                when (result) {
                    // Ya anulada en otra pantalla: el detalle lo muestra, sin error.
                    VoidSupplierReturnResult.Voided, VoidSupplierReturnResult.AlreadyVoided ->
                        Overlay(justVoided = result == VoidSupplierReturnResult.Voided)

                    VoidSupplierReturnResult.NotFound,
                    VoidSupplierReturnResult.ReasonNotAllowed,
                    VoidSupplierReturnResult.NoteTooLong,
                    is VoidSupplierReturnResult.UnknownTimeZone,
                    VoidSupplierReturnResult.LedgerDamaged,
                    is VoidSupplierReturnResult.StorageFailed,
                    -> it.copy(voiding = false, voidFailed = true)
                }
            }
        }
    }

    private sealed interface Loaded {
        data class Read(val detail: SupplierReturnDetail?) : Loaded

        data object Failed : Loaded
    }

    private data class Overlay(
        val confirmVoid: Boolean = false,
        val voiding: Boolean = false,
        val voidFailed: Boolean = false,
        val justVoided: Boolean = false,
    )

    private companion object {
        const val STOP_MILLIS = 5_000L
    }
}
