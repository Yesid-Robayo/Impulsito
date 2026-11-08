package io.github.yesidrobayo.shopledger.ui.payment

/**
 * Por qué no se guardó, en lo que importa para el texto que ve la persona: si el teléfono se quedó
 * sin espacio, que ella puede resolver, o cualquier otra falla del almacenamiento. En los dos casos
 * no quedó nada a medias, y lo escrito se conserva.
 */
enum class SaveProblem {
    OUT_OF_SPACE,
    OTHER,
    ;

    companion object {
        /** El problema de una falla de la base, según sea o no de espacio. */
        fun of(outOfSpace: Boolean): SaveProblem = if (outOfSpace) OUT_OF_SPACE else OTHER
    }
}
