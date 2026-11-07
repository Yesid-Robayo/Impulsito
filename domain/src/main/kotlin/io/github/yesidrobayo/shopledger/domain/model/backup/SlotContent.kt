package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant

/**
 * Lo que el estado local de las ranuras dice que hay en una de ellas.
 *
 * Una ranura vacía o a medias está **abierta**: no tiene nada que perder, porque abrir el archivo
 * para escribir ya lo vació. Solo [Holding] cuenta como copia.
 */
sealed interface SlotContent {
    /** `true` si la ranura no guarda ninguna copia que una escritura pudiera destruir. */
    val isOpen: Boolean

    /** La ranura nunca recibió una copia. */
    data object Empty : SlotContent {
        override val isOpen: Boolean get() = true
    }

    /**
     * Se abrió para escribir la generación [writingGeneration] y nunca se confirmó.
     *
     * El archivo pudo quedar truncado o con una parte, así que no cuenta como copia: la siguiente
     * escritura la usa antes que cualquier otra.
     */
    data class HalfWritten(val writingGeneration: Long) : SlotContent {
        init {
            require(writingGeneration > 0) { "La generación en escritura debe ser positiva" }
        }

        override val isOpen: Boolean get() = true
    }

    /** Guarda una copia confirmada tras releerla. */
    data class Holding(val copy: SlotCopy) : SlotContent {
        override val isOpen: Boolean get() = false
    }
}

/**
 * Los datos de una copia confirmada en una ranura que necesita la elección de ranura.
 *
 * [records] y [catalogCount] son los conteos neutros de la copia. Los dos importan: un negocio sin
 * movimientos tiene cero registros, tenga diez productos o cuatrocientos, y solo el catálogo
 * distingue esas copias.
 *
 * @property generation la generación de la copia: el orden confiable entre copias.
 * @property writtenAt cuándo se escribió, según el reloj del teléfono. Solo decide si el ancla
 *     venció.
 * @property valid si el validador la dejó restaurable sin reparar.
 * @property records cuántos registros del negocio tiene.
 * @property catalogCount cuántos productos tiene su catálogo.
 */
data class SlotCopy(
    val generation: Long,
    val writtenAt: Instant,
    val valid: Boolean,
    val records: Long,
    val catalogCount: Long,
) {
    init {
        require(generation > 0) { "La generación de una copia debe ser positiva" }
        require(records >= 0) { "Los registros de una copia no pueden ser negativos" }
        require(catalogCount >= 0) { "El catálogo de una copia no puede ser negativo" }
    }
}
