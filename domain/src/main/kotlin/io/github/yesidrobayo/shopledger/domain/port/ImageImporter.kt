package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind

/**
 * Trae una imagen que eligió la persona: la lee con tope, la decodifica con tamaño acotado, la
 * recodifica a WebP, lo que quita EXIF y ubicación, y la guarda en su archivo, nombrado por su suma.
 * El original nunca se guarda. Lo implementa `:data`.
 *
 * No escribe en la base: el caso de uso anota la imagen en su transacción. Lee y escribe archivos,
 * así que nunca corre en el hilo principal.
 */
fun interface ImageImporter {
    /**
     * Importa la imagen de [source], el documento que dio el selector del sistema, con el tamaño que
     * pide [kind]. [source] es un identificador opaco: nunca se guarda ni se registra.
     */
    suspend fun import(source: String, kind: MediaKind): ImageImport
}
