package io.github.yesidrobayo.shopledger.ui.payment

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord

/** Lo que muestra la lista de métodos de pago. */
sealed interface PaymentMethodsUiState {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : PaymentMethodsUiState

    /** No se pudo leer. Se puede reintentar, y nada se perdió. */
    data object ReadError : PaymentMethodsUiState

    /**
     * La lista: [active] en el orden de cobro y [archived] aparte, plegados salvo que
     * [archivedExpanded]. Si [active] está vacía, todos quedaron archivados y la pantalla lo dice.
     *
     * [sorting] es el orden que la persona arma, mientras ordena, o `null` fuera de ese modo.
     */
    data class Ready(
        val active: List<PaymentMethodRecord>,
        val archived: List<PaymentMethodRecord>,
        val archivedExpanded: Boolean = false,
        val sorting: SortingState? = null,
    ) : PaymentMethodsUiState
}

/** El modo de ordenar: el orden que se está armando y cómo va guardarlo. */
data class SortingState(
    val order: List<PaymentMethodRecord>,
    val status: SortingStatus = SortingStatus.Editing,
)

/** Cómo va guardar el orden. */
sealed interface SortingStatus {
    data object Editing : SortingStatus

    data object Saving : SortingStatus

    /**
     * Otro cambio entró mientras se ordenaba, como un método nuevo o uno archivado. El orden armado
     * ya no sirve: la pantalla lo dice y vuelve a empezar con la lista de hoy.
     */
    data object ListChanged : SortingStatus

    /** La base no pudo guardar. El orden armado se conserva para reintentar. */
    data class Failed(val problem: SaveProblem) : SortingStatus
}

/** Lo que la persona hace en la lista. */
sealed interface PaymentMethodsAction {
    data object Back : PaymentMethodsAction

    data object Retry : PaymentMethodsAction

    data object NewMethod : PaymentMethodsAction

    data class OpenMethod(val id: Long) : PaymentMethodsAction

    data object ToggleArchived : PaymentMethodsAction

    data object StartSorting : PaymentMethodsAction

    data object CancelSorting : PaymentMethodsAction

    /** Mueve el método de la posición [from] a [to] del orden que se arma. */
    data class Move(val from: Int, val to: Int) : PaymentMethodsAction

    data object SaveOrder : PaymentMethodsAction
}
