package io.github.yesidrobayo.shopledger.domain.model.catalog

/** Qué pasa al guardar con la foto de un producto. */
sealed interface ProductPhotoChange {
    /** Queda la que tenía, o ninguna en un producto nuevo. */
    data object Keep : ProductPhotoChange

    /** Se quita. Si nada más la usa, la imagen queda marcada para borrarse más adelante. */
    data object Remove : ProductPhotoChange

    /** Se usa [image], ya recodificada y guardada en su archivo. */
    data class Use(val image: StoredImage) : ProductPhotoChange
}
