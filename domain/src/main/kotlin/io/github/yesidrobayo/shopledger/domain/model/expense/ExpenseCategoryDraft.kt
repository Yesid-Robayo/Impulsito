package io.github.yesidrobayo.shopledger.domain.model.expense

/**
 * Lo que la persona escribe en la hoja de una categoría de gasto, tal cual, antes de revisarlo.
 *
 * En una categoría inicial, [name] en blanco conserva el nombre traducido: la categoría sigue el
 * idioma de la interfaz. Con un nombre propio, deja de seguirlo. [isFixed] marca un gasto fijo, que
 * cuenta para el punto de equilibrio.
 */
data class ExpenseCategoryDraft(val name: String, val isFixed: Boolean = false)

/** Lo que impide guardar una categoría de gasto tal como se escribió. */
enum class ExpenseCategoryInputError {
    /**
     * El nombre queda vacío tras recortar espacios, o hecho solo de marcas sin letra, en una
     * categoría que no tiene nombre traducido al que volver.
     */
    NAME_EMPTY,

    /** El nombre pasa de 40 caracteres. */
    NAME_TOO_LONG,
}
