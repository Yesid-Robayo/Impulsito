package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Lo que impide guardar una receta tal como se armó. La pantalla marca cada fila con su motivo, y
 * lo armado se conserva.
 */
sealed interface RecipeIssue {
    /** La fila número [index] de la receta, contando desde cero, no se puede guardar así. */
    data class Line(val index: Int, val problem: RecipeLineProblem) : RecipeIssue

    /** La receta pasa del tope de componentes, el mismo que el de líneas de un documento. */
    data object TooManyComponents : RecipeIssue
}

/** Por qué una fila de la receta no se puede guardar. */
enum class RecipeLineProblem {
    /** La cantidad es cero o negativa, o pasa del tope de una línea. */
    QUANTITY_OUT_OF_RANGE,

    /** El componente es el mismo combo. */
    SAME_PRODUCT,

    /** El componente ya está en una fila anterior: se suma ahí la cantidad. */
    REPEATED,

    /**
     * El componente ya no existe, o está archivado y no estaba en la receta. Uno archivado que ya
     * estaba se conserva: el combo sigue vendiéndose.
     */
    UNAVAILABLE,

    /** El componente ya contiene a este combo, directamente o dentro de otro: sería un ciclo. */
    CYCLE,

    /** Con este componente habría un tercer nivel de combos, y el máximo es dos. */
    TOO_DEEP,
}
