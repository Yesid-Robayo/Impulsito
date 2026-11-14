package io.github.yesidrobayo.shopledger.ui.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.rule.document.SaleReceiptDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Lo que el comprobante lee de los ajustes: la plantilla, el nombre del negocio y su zona. */
data class SaleReceiptSource(val template: ReceiptTemplateSource, val zone: BusinessTimeZone?)

/** Cómo se comparte el comprobante. */
enum class ReceiptShare {
    /** Un PDF en la hoja del formato regional. */
    PDF,

    /** Una imagen larga, para mensajería. */
    IMAGE,
}

/** Lo que muestra el comprobante de una venta. */
sealed interface SaleReceiptUiState {
    data object Loading : SaleReceiptUiState

    data object NotFound : SaleReceiptUiState

    data object ReadFailed : SaleReceiptUiState

    /**
     * El comprobante listo para ver y compartir. [logo] es la imagen del logo, que se lee de su
     * archivo; [units] y [zone] completan lo que el papel escribe. [sharing] es lo que se está
     * creando para compartir, y [shareFailed], lo que no se pudo crear: la venta no cambia.
     */
    data class Ready(
        val document: ReceiptDocument,
        val logo: StoredImage?,
        val units: Map<Long, MeasureUnit>,
        val zone: BusinessTimeZone?,
        val sharing: ReceiptShare? = null,
        val shareFailed: ReceiptShare? = null,
    ) : SaleReceiptUiState
}

/** Lo que la persona hace en el comprobante. */
sealed interface SaleReceiptAction {
    data class Share(val kind: ReceiptShare) : SaleReceiptAction

    /** La ruta terminó de crear el archivo y abrir el selector, o no pudo. */
    data class ShareFinished(val shared: Boolean) : SaleReceiptAction

    data object Retry : SaleReceiptAction

    data object Back : SaleReceiptAction

    data object EditTemplate : SaleReceiptAction
}

/**
 * El comprobante de una venta, para verlo y compartirlo como PDF o imagen.
 *
 * Observa la venta y la plantilla: si la venta se anula o cambia la plantilla, el documento se
 * rehace. Sale de [SaleReceiptDocument], que no tiene costo, margen, ganancia, comisión ni nota.
 * Compartir lo hace la ruta, que tiene el disco y el selector; aquí solo se anota qué se pidió y
 * cómo terminó, una cosa a la vez.
 *
 * @param observeDetail la venta con sus líneas, pagos y devoluciones, o `null` si no existe.
 * @param observeSource la plantilla, el nombre del negocio y su zona.
 * @param observeUnits las unidades de medida, para escribir las cantidades.
 * @param customerName el nombre del cliente de la venta, o `null` si ya no tiene datos.
 * @param loadImage la imagen guardada de un id, para el logo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleReceiptViewModel(
    private val saleId: Long,
    private val observeDetail: (Long) -> Flow<SaleDetail?>,
    private val observeSource: () -> Flow<SaleReceiptSource>,
    private val observeUnits: () -> Flow<List<MeasureUnit>>,
    private val customerName: suspend (Long) -> String?,
    private val loadImage: suspend (Long) -> StoredImage?,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val sharing = MutableStateFlow(Sharing())

    private val content: Flow<SaleReceiptUiState> = attempts.flatMapLatest {
        combine(observeDetail(saleId), observeSource(), observeUnits()) { detail, source, units ->
            if (detail == null) return@combine SaleReceiptUiState.NotFound
            val template = source.template
            val customer = detail.sale.content.customerId?.let { customerName(it) }
            val document = SaleReceiptDocument.of(
                detail,
                template.receipt,
                template.businessName,
                customer,
            )
            SaleReceiptUiState.Ready(
                document = document,
                logo = template.receipt.logoMediaId?.let { loadImage(it) },
                units = units.associateBy { it.id },
                zone = source.zone,
            )
        }.flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(SaleReceiptUiState.ReadFailed) }
    }

    val uiState: StateFlow<SaleReceiptUiState> =
        combine(content, sharing) { state, shown ->
            if (state is SaleReceiptUiState.Ready) {
                state.copy(sharing = shown.working, shareFailed = shown.failed)
            } else {
                state
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SaleReceiptUiState.Loading,
        )

    fun onAction(action: SaleReceiptAction) {
        when (action) {
            is SaleReceiptAction.Share -> sharing.update {
                // Un archivo a la vez: un segundo toque no crea otro.
                if (it.working != null) it else Sharing(working = action.kind)
            }

            is SaleReceiptAction.ShareFinished -> sharing.update {
                Sharing(failed = it.working.takeIf { _ -> !action.shared })
            }

            SaleReceiptAction.Retry -> attempts.update { it + 1 }

            // Los resuelve la ruta: navegan.
            SaleReceiptAction.Back, SaleReceiptAction.EditTemplate -> Unit
        }
    }

    private data class Sharing(val working: ReceiptShare? = null, val failed: ReceiptShare? = null)

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
