package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant

/**
 * La última copia entregada a la nube del teléfono, para revisarla en esa nube: su nombre, su tamaño
 * y la hora en que la app la escribió, que es lo que la persona debe ver allí, o una más nueva.
 *
 * @property fileName el nombre del archivo como lo muestra esa nube, o nulo si no se sabe.
 * @property generation la generación de esa copia: la revisión queda anotada con ella.
 * @property documentUri el documento, para abrirlo en la app de esa nube. Nunca va a un registro.
 * @property lastCheckAt la última vez que la persona dijo haberla visto, o nula si nunca.
 */
data class HandoffCopy(
    val fileName: String?,
    val sizeBytes: Long,
    val writtenAt: Instant,
    val generation: Long,
    val documentUri: String,
    val lastCheckAt: Instant?,
) {
    // La dirección del documento no sale en un texto de depuración ni en un registro.
    override fun toString(): String = "HandoffCopy(generation=$generation)"
}
