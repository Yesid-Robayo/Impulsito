package io.github.yesidrobayo.shopledger.domain.model.payment

import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage

/** Qué pasa al guardar con la imagen de cobro de un método, como su código QR. */
sealed interface InstructionsImageChange {
    /** Queda la que tenía, o ninguna en un método nuevo. */
    data object Keep : InstructionsImageChange

    /** Se quita. Si nada más la usa, la imagen queda marcada para borrarse más adelante. */
    data object Remove : InstructionsImageChange

    /** Se usa [image], ya recodificada y guardada en su archivo. */
    data class Use(val image: StoredImage) : InstructionsImageChange
}
