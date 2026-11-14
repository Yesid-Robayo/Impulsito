package io.github.yesidrobayo.shopledger.ui.quote

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRow
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

/** Lo que la lista de cotizaciones lee. */
interface QuoteListOperations {
    /** Las de [status], de la más nueva a la más vieja. */
    fun list(status: QuoteStatus): Flow<List<QuoteRow>>

    /** Si hay al menos una cotización, en cualquier estado. */
    fun hasAny(): Flow<Boolean>

    /** Hoy en la zona del negocio, o `null` si el teléfono no la conoce. */
    fun today(): Flow<BusinessDate?>
}

/** Una cotización como la muestra su fila. [expired] es abierta con la vigencia ya pasada. */
@Immutable
data class QuoteItem(
    val id: Long,
    val customerName: String?,
    val validUntil: BusinessDate?,
    val total: Money,
    val status: QuoteStatus,
    val expired: Boolean,
)

/** Lo que muestra la lista. */
sealed interface QuotesContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : QuotesContent

    /** No se pudo leer. Se puede reintentar. */
    data object Failed : QuotesContent

    /** No hay ninguna cotización: se invita a crear la primera. */
    data object Empty : QuotesContent

    /** Hay cotizaciones, pero ninguna con el estado elegido. */
    data object FilterEmpty : QuotesContent

    data class Rows(val items: List<QuoteItem>) : QuotesContent
}

/**
 * La lista de cotizaciones: el estado elegido en los chips, lo que se lee con él y, en una ventana
 * ancha, la cotización abierta a la derecha.
 */
@Immutable
data class QuotesUiState(
    val filter: QuoteStatus = QuoteStatus.OPEN,
    val content: QuotesContent = QuotesContent.Loading,
    val selectedId: Long? = null,
)

/** Lo que la persona hace en la lista. */
sealed interface QuotesAction {
    data class SelectFilter(val status: QuoteStatus) : QuotesAction

    /** Abre la cotización: encima en un teléfono, a la derecha en una ventana ancha. */
    data class Open(val quoteId: Long) : QuotesAction

    data object CloseDetail : QuotesAction

    data object Retry : QuotesAction

    /** Navegación: las resuelve la ruta. */
    data object New : QuotesAction

    data object Back : QuotesAction
}

/**
 * La lista de cotizaciones. Empieza en `Abiertas`. Una abierta con la vigencia pasada se marca
 * `Vencida`, calculado con la fecha de hoy: su estado no cambia solo. Una falla de lectura no
 * cierra la pantalla: se dice y se puede reintentar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotesViewModel(private val operations: QuoteListOperations) : ViewModel() {
    private val filter = MutableStateFlow(QuoteStatus.OPEN)
    private val selected = MutableStateFlow<Long?>(null)
    private val attempts = MutableStateFlow(0)

    private val rows: Flow<List<QuoteRow>?> = combine(filter, attempts) { status, _ -> status }
        .flatMapLatest { status ->
            operations.list(status).map<List<QuoteRow>, List<QuoteRow>?> { it }
                .catch { emit(null) }
        }

    val uiState: StateFlow<QuotesUiState> = combine(
        filter,
        rows,
        operations.hasAny().catch { emit(true) },
        operations.today().catch { emit(null) },
        selected,
    ) { status, read, any, today, open ->
        QuotesUiState(filter = status, content = contentOf(read, any, today), selectedId = open)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        QuotesUiState(),
    )

    fun onAction(action: QuotesAction) {
        when (action) {
            is QuotesAction.SelectFilter -> {
                filter.value = action.status
                selected.value = null
            }

            is QuotesAction.Open -> selected.value = action.quoteId

            QuotesAction.CloseDetail -> selected.value = null

            QuotesAction.Retry -> attempts.update { it + 1 }

            QuotesAction.New, QuotesAction.Back -> Unit
        }
    }

    private fun contentOf(
        read: List<QuoteRow>?,
        any: Boolean,
        today: BusinessDate?,
    ): QuotesContent = when {
        read == null -> QuotesContent.Failed
        read.isNotEmpty() -> QuotesContent.Rows(read.map { itemOf(it, today) })
        any -> QuotesContent.FilterEmpty
        else -> QuotesContent.Empty
    }

    private fun itemOf(row: QuoteRow, today: BusinessDate?): QuoteItem {
        val quote = row.quote
        val validUntil = quote.validUntil
        return QuoteItem(
            id = quote.id,
            customerName = row.customerName,
            validUntil = validUntil,
            total = quote.totals.total,
            status = quote.status,
            expired = quote.status == QuoteStatus.OPEN && validUntil != null && today != null &&
                validUntil < today,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
