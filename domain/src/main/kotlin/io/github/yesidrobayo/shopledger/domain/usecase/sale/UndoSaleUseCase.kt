package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.VoidReason

/**
 * Deshacer una venta recién confirmada, desde el aviso que sigue a registrarla.
 *
 * No es un borrado ni un camino aparte: es la anulación de siempre, con el motivo
 * [VoidReason.UNDO] y sin nota, en una sola transacción. La venta queda en el historial, marcada
 * como anulada, y cada fila de venta del libro recibe su compensatorio, porque nada se borra.
 *
 * Cuánto dura el aviso lo decide la pantalla, no este caso de uso: pasado ese tiempo, la persona
 * anula desde el detalle, con el motivo que elija.
 */
class UndoSaleUseCase(private val voidSale: VoidSaleUseCase) {
    suspend operator fun invoke(saleId: Long): VoidSaleResult =
        voidSale(saleId, VoidReason.UNDO, note = null)
}
