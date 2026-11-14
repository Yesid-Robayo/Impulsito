package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import kotlinx.coroutines.flow.Flow

/**
 * Las imágenes anotadas por su id, otra vez cada vez que cambian: así una lista halla el archivo de
 * cada foto sin leer una fila por producto, y una foto nueva o quitada en otra pantalla llega sola.
 */
class ObserveStoredImagesUseCase(private val media: MediaAssetRepository) {
    operator fun invoke(): Flow<Map<Long, StoredImage>> = media.observeImages()
}
