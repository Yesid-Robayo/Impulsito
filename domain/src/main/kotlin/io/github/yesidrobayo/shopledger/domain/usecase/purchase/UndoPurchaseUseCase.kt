package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.VoidReason

/**
 * Deshacer una compra recién registrada, desde el aviso que sigue a registrarla.
 *
 * No es un borrado ni un camino aparte: es la anulación de siempre, con el motivo
 * [VoidReason.UNDO] y sin nota, en una sola transacción. La compra queda en el historial, anulada,
 * y cada fila de compra del libro recibe su compensatorio. Cuánto dura el aviso lo decide la
 * pantalla: pasado ese tiempo, la persona anula desde el detalle, con el motivo que elija.
 */
class UndoPurchaseUseCase(private val voidPurchase: VoidPurchaseUseCase) {
    suspend operator fun invoke(purchaseId: Long): VoidPurchaseResult =
        voidPurchase(purchaseId, VoidReason.UNDO, note = null)
}
