package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountMarks

/**
 * Lo que la persona marcó de la cuenta de la nube, en este teléfono. Es de la instalación, no del
 * negocio: no viaja en una copia, y restaurar una no lo cambia. Nada de esto usa la red, y la app
 * nunca lo comprueba en el servicio.
 */
interface CloudAccountChecklistPort {
    /** Lo marcado, o nulo si nunca se marcó nada o lo guardado no se entiende. */
    suspend fun load(): CloudAccountMarks?

    /** Guarda [marks] entero. `false` si no quedó en el disco: entonces sigue lo de antes. */
    suspend fun save(marks: CloudAccountMarks): Boolean
}
