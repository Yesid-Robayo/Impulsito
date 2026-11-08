package io.github.yesidrobayo.shopledger.domain.model.catalog

/** Cómo terminó traer una imagen elegida por la persona. */
sealed interface ImageImport {
    /** Quedó recodificada y guardada en su archivo. */
    data class Imported(val image: StoredImage) : ImageImport

    /**
     * No se pudo leer como imagen: no es una imagen, está dañada, pasa del tope de lectura o ya no
     * está disponible. Nada se guardó, y la persona puede elegir otra.
     */
    data object Unreadable : ImageImport

    /** El teléfono no tiene espacio para guardarla. Nada se guardó. */
    data object OutOfSpace : ImageImport
}
