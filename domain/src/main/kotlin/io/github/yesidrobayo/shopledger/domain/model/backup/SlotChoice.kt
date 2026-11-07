package io.github.yesidrobayo.shopledger.domain.model.backup

/** En qué ranura cae una copia nueva, o por qué no se escribe en ninguna. */
sealed interface SlotChoice {
    /** Se escribe en [slot]. Desde que se abre, lo que había en esa ranura se da por perdido. */
    data class Write(val slot: BackupSlot) : SlotChoice

    /**
     * La copia es fatal y no se escribe: los cambios siguen sin respaldar, y el estado del respaldo
     * queda con el fallo de invariante fatal.
     */
    data object FatalCopy : SlotChoice

    /**
     * Las tres ranuras están protegidas y no se escribe nada. Pasa tras una restauración o una
     * pérdida de datos, sumada a copias inválidas: el estado del respaldo queda en conflicto porque
     * la base local tiene menos que una copia, y la persona decide.
     *
     * @property conflictSlot la ranura que el conflicto muestra: la que protege más registros, y
     *     entre iguales la de más catálogo. Es `null` solo si ninguna protege datos de más, lo que
     *     ocurre si la cabeza que recuerda el estado del respaldo no es la de mayor generación en
     *     las ranuras.
     */
    data class AllLocked(val conflictSlot: BackupSlot?) : SlotChoice
}
