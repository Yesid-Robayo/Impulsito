package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter

/**
 * Trae la imagen con los datos de cobro que eligió la persona, como su código QR, recodificada y sin
 * EXIF. Todavía no la asigna al método: eso pasa al guardar el formulario, en la misma transacción
 * que lo demás.
 */
class ImportPaymentImageUseCase(private val importer: ImageImporter) {
    suspend operator fun invoke(source: String): ImageImport =
        importer.import(source, MediaKind.PAYMENT_QR)
}
