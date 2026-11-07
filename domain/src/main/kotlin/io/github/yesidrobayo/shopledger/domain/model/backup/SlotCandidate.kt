package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * La copia nueva que busca ranura, con lo que dijo de ella el validador y sus conteos neutros.
 *
 * @property marks si cuenta como válida y si es fatal. Una copia fatal nunca se escribe en una
 *     ranura.
 * @property records cuántos registros del negocio tiene.
 * @property catalogCount cuántos productos tiene su catálogo.
 */
data class SlotCandidate(val marks: NewCopyMarks, val records: Long, val catalogCount: Long) {
    init {
        require(records >= 0) { "Los registros de una copia no pueden ser negativos" }
        require(catalogCount >= 0) { "El catálogo de una copia no puede ser negativo" }
    }
}
