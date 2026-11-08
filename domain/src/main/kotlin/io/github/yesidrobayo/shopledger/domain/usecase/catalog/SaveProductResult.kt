package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogReference
import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/**
 * Cómo termina crear o editar un producto. Salvo [Saved], no se guardó nada y lo escrito en el
 * formulario se conserva.
 */
sealed interface SaveProductResult {
    /** Quedó guardado, con todo lo que la operación escribe, en una sola transacción. */
    data class Saved(val productId: Long) : SaveProductResult

    /** Lo escrito no se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<ProductFormIssue>) : SaveProductResult

    /**
     * Otro producto sin archivar ya tiene ese nombre, comparado sin mayúsculas ni tildes.
     * [existingName] es su nombre tal como se guardó, para nombrarlo en el mensaje.
     */
    data class DuplicateName(val existingName: String) : SaveProductResult

    /** Otro producto sin archivar ya tiene ese código de barras. */
    data class DuplicateBarcode(val existingName: String) : SaveProductResult

    /** La categoría, el proveedor o la unidad elegidos ya no existen o ya no se pueden elegir. */
    data class MissingReference(val reference: CatalogReference) : SaveProductResult

    /** El producto tiene historia, y la edición cambia algo que la historia deja fijo. */
    data class LockedByHistory(val fields: Set<HistoryLockedField>) : SaveProductResult

    /**
     * El costo inicial ya no cambia: el producto tiene filas en su libro, y el costo lo lleva el
     * libro. Se corrige con una corrección de costo, que deja su propia fila.
     */
    data object CostLockedByMovements : SaveProductResult

    /** Un producto archivado no cambia de precio ni de costo: hay que reactivarlo antes. */
    data object ArchivedPriceLocked : SaveProductResult

    /** El producto que se editaba ya no existe. */
    data object NotFound : SaveProductResult

    /** La zona del negocio no se reconoce, y sin ella no hay fecha de negocio que guardar. */
    data object UnknownTimeZone : SaveProductResult

    /**
     * El producto lleva precio o costo, y la moneda del negocio sigue sin confirmar: un monto sin
     * moneda no tendría unidad. La pantalla pide confirmarla primero, sin perder lo escrito.
     */
    data object CurrencyUnconfirmed : SaveProductResult

    /** El stock inicial deja el stock o su valor por encima de [limit]. */
    data class AboveLimit(val limit: Limit) : SaveProductResult

    /** La base no pudo escribir. No quedó nada a medias. */
    data class StorageFailed(val failure: StorageFailure) : SaveProductResult
}
