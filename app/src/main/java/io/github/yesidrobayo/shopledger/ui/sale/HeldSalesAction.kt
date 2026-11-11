package io.github.yesidrobayo.shopledger.ui.sale

/** Lo que la persona hace en las ventas en espera. La navegación la resuelve la ruta. */
sealed interface HeldSalesAction {
    /** Tocar una fila: retoma la venta, o pregunta antes si hay otra en edición. */
    data class Resume(val heldSaleId: Long) : HeldSalesAction

    data object ConfirmSwap : HeldSalesAction

    data class AskDiscard(val heldSaleId: Long) : HeldSalesAction

    data object ConfirmDiscard : HeldSalesAction

    data class OpenRename(val heldSaleId: Long) : HeldSalesAction

    data class RenameChange(val text: String) : HeldSalesAction

    data object ConfirmRename : HeldSalesAction

    data object CloseRename : HeldSalesAction

    data object DismissDialog : HeldSalesAction

    data object RetryRead : HeldSalesAction

    data object NoticeShown : HeldSalesAction

    data object ResumeShown : HeldSalesAction

    /** Ir a vender desde la lista vacía. */
    data object GoSell : HeldSalesAction

    data object Back : HeldSalesAction
}
