package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck

/** Cómo termina un ajuste de stock. Salvo [Adjusted], no se escribió nada. */
sealed interface StockAdjustmentResult {
    /** Quedó en el libro como la fila [movementId], y el producto quedó en [onHandAfter]. */
    data class Adjusted(val movementId: Long, val onHandAfter: QuantityMilli) :
        StockAdjustmentResult

    /** Lo contado es lo que dice el libro: no hay nada que corregir. */
    data object NoDifference : StockAdjustmentResult

    data object NotFound : StockAdjustmentResult

    /** Un producto archivado no recibe ajustes: hay que reactivarlo antes. */
    data object ProductArchived : StockAdjustmentResult

    /** El producto no lleva stock propio: no tiene libro que ajustar. */
    data object NotTracked : StockAdjustmentResult

    /** La ubicación elegida ya no existe o se archivó: hay que elegir otra. */
    data object LocationUnavailable : StockAdjustmentResult

    /** La cantidad no vale para la unidad del producto, por [check]. */
    data class InvalidQuantity(val check: UnitQuantityCheck) : StockAdjustmentResult

    /** El motivo solo resta: lo dañado o lo regalado nunca entra al stock. */
    data object IncreaseNotAllowed : StockAdjustmentResult

    /** El motivo pide una nota que explique el ajuste, y no la tiene. */
    data object NoteRequired : StockAdjustmentResult

    /** La nota pasa de [maxLength] caracteres. */
    data class NoteTooLong(val maxLength: Int) : StockAdjustmentResult

    /** Dejaría la cantidad, el stock o su valor por encima de [limit]. */
    data class AboveLimit(val limit: Limit) : StockAdjustmentResult

    /** El lote elegido no existe o es de otro producto. */
    data object LotNotFound : StockAdjustmentResult

    /**
     * Del lote elegido sale más de los [available] que tiene. Solo el lote sin asignar puede
     * quedar negativo: un lote con código nunca da más de lo que recibió.
     */
    data class AboveLotStock(val available: QuantityMilli) : StockAdjustmentResult

    /** La zona del negocio no se reconoce, y sin ella no hay fecha de negocio que guardar. */
    data object UnknownTimeZone : StockAdjustmentResult

    /** El ajuste tiene valor, y la moneda del negocio sigue sin confirmar. */
    data object CurrencyUnconfirmed : StockAdjustmentResult

    data class StorageFailed(val failure: StorageFailure) : StockAdjustmentResult
}

/**
 * Cómo terminan varios ajustes pedidos juntos, en una sola transacción. Salvo [Adjusted], no se
 * escribió nada: si una línea se rechaza, ninguna queda.
 */
sealed interface StockAdjustmentsResult {
    /** Quedaron en el libro como las filas [movementIds], en el orden pedido. */
    data class Adjusted(val movementIds: List<Long>) : StockAdjustmentsResult

    /** Todo lo contado es lo que dice el libro: no hay nada que corregir. */
    data object NoDifference : StockAdjustmentsResult

    /** La línea [index] se rechazó por [result], y nada se escribió. */
    data class Rejected(val index: Int, val result: StockAdjustmentResult) :
        StockAdjustmentsResult

    data class StorageFailed(val failure: StorageFailure) : StockAdjustmentsResult
}

/** Cómo termina anular un ajuste o un inventario inicial. Salvo [Voided], no se escribió nada. */
sealed interface VoidAdjustmentResult {
    /** El compensatorio quedó en el libro como la fila [movementId]. */
    data class Voided(val movementId: Long) : VoidAdjustmentResult

    data object NotFound : VoidAdjustmentResult

    /**
     * La fila no es un ajuste ni un inventario inicial: una venta o una compra se anulan desde su
     * documento, y una corrección de costo se corrige con otra.
     */
    data object NotVoidable : VoidAdjustmentResult

    /** Ya tiene su compensatorio: anularla otra vez la descontaría dos veces. */
    data object AlreadyVoided : VoidAdjustmentResult

    /** La zona del negocio no se reconoce, y sin ella no hay fecha que darle al compensatorio. */
    data object UnknownTimeZone : VoidAdjustmentResult

    /**
     * El libro del producto no se puede reproducir: tiene una fila que contradice su tipo o cifras
     * que no caben. La app no escribe un libro así; puede venir de una copia dañada.
     */
    data object LedgerDamaged : VoidAdjustmentResult

    data class StorageFailed(val failure: StorageFailure) : VoidAdjustmentResult
}
