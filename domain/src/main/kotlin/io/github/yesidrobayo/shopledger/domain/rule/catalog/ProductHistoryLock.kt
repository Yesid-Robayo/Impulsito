package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails

/**
 * Qué no puede cambiar la edición de un producto que ya tiene historia.
 *
 * El libro de stock va siempre en la unidad base del producto: una fila escrita en kilogramos que
 * se leyera en gramos movería mil veces menos de lo que movió. Por eso, con historia, la unidad
 * base queda fija, y con ella el modo de stock y los lotes. Sin historia, todo se puede cambiar.
 *
 * La pantalla muestra bloqueado lo que devuelve [lockedFields], y el caso de uso rechaza la edición
 * si [lockedChanges] no está vacío. La base tiene la misma regla en su guarda, como última defensa.
 */
object ProductHistoryLock {
    /** Lo que la edición no puede cambiar, según si el producto [hasHistory]. */
    fun lockedFields(hasHistory: Boolean): Set<HistoryLockedField> =
        if (hasHistory) HistoryLockedField.entries.toSet() else emptySet()

    /**
     * Lo que [requested] cambia respecto de [current] y la historia no deja cambiar. Vacío: la
     * edición se puede guardar. Lo demás de la edición no cuenta.
     */
    fun lockedChanges(
        current: ProductDetails,
        requested: ProductDetails,
        hasHistory: Boolean,
    ): Set<HistoryLockedField> = lockedFields(hasHistory).filterTo(mutableSetOf()) { field ->
        when (field) {
            HistoryLockedField.BASE_UNIT -> current.baseUnitId != requested.baseUnitId
            HistoryLockedField.STOCK_MODE -> current.stockMode != requested.stockMode
            HistoryLockedField.TRACKS_LOTS -> current.tracksLots != requested.tracksLots
        }
    }
}
