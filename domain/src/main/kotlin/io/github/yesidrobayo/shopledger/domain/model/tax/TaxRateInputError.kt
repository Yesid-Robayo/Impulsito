package io.github.yesidrobayo.shopledger.domain.model.tax

/**
 * Por qué no se guarda lo escrito en una tasa. Cada uno es un error de su campo, y la pantalla los
 * muestra todos a la vez, sin borrar nada de lo escrito.
 */
enum class TaxRateInputError {
    /** El nombre está vacío, o solo tiene espacios o marcas. */
    NAME_BLANK,

    /** El nombre pasa de 40 caracteres. */
    NAME_TOO_LONG,

    /** Otra tasa vigente ya tiene ese nombre, sin contar mayúsculas ni tildes. */
    NAME_TAKEN,

    /** El porcentaje no está entre 0 % y 100 %. */
    RATE_OUT_OF_RANGE,
}
