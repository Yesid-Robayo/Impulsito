package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter

/**
 * Trae la foto de producto que eligió la persona, recodificada a su tamaño y sin EXIF. Todavía no
 * la asigna: eso pasa al guardar el producto, en la misma transacción que lo demás. Si la persona
 * no guarda, el archivo queda sin fila y la limpieza de fotos lo borra.
 */
class ImportProductPhotoUseCase(private val importer: ImageImporter) {
    suspend operator fun invoke(source: String): ImageImport =
        importer.import(source, MediaKind.PRODUCT_PHOTO)
}
