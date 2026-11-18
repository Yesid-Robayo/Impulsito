package io.github.yesidrobayo.shopledger.domain.model.phrase

/**
 * Lo que hace con la tarjeta a la vista una frase que no se aplicó como corrección, ya leída
 * entera. Ninguno borra la tarjeta en silencio: o la suma, o la deja como estaba y dice por qué.
 */
sealed interface CardFollowUp {
    /**
     * La frase es de la misma operación, o no dice ninguna y hereda la de la tarjeta: sus líneas se
     * suman a la tarjeta o corrigen las del mismo producto, y queda [draft].
     */
    data class Joined(val draft: PhraseDraft) : CardFollowUp

    /**
     * La frase dice el tipo o la categoría de [productId], un producto que ya existe en el catálogo:
     * eso se cambia en el producto, no en la tarjeta, que sigue como estaba.
     */
    data class KnownProduct(val productId: Long) : CardFollowUp

    /**
     * La frase es otra operación, [draft]: antes de dejar la tarjeta a la vista, se pregunta si
     * empezar otra.
     */
    data class OtherOperation(val draft: PhraseDraft) : CardFollowUp

    /**
     * La frase empieza con "no" y dice otra operación con su verbo: la persona corrige lo que dijo
     * antes, y [draft] reemplaza la tarjeta, como siempre.
     */
    data class Replaced(val draft: PhraseDraft) : CardFollowUp

    /** No se entendió: la tarjeta sigue como estaba, con el aviso. */
    data object NotUnderstood : CardFollowUp
}
