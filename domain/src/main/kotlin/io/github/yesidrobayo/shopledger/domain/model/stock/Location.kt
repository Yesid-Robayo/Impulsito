package io.github.yesidrobayo.shopledger.domain.model.stock

import kotlin.time.Instant

/**
 * Una ubicación del stock guardada: la inicial, que nace con la base, o una que creó la persona,
 * como un depósito o un puesto de feria.
 *
 * - [systemKey] es la clave estable, en inglés, de la ubicación inicial, `MAIN`. Mientras la
 *   persona no la renombra se muestra con el texto traducido de esa clave.
 * - La inicial nunca se archiva: siempre hay dónde anotar el stock.
 * - Archivada deja de ofrecerse, pero sigue explicando lo que ya se registró en ella.
 *
 * Lanza [IllegalArgumentException] si no tiene ni nombre ni clave: no habría con qué mostrarla.
 */
data class Location(
    val id: Long,
    val systemKey: String?,
    val details: LocationDetails,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(details.name != null || systemKey != null) {
            "Una ubicación sin nombre necesita su clave inicial"
        }
    }

    /** Si está archivada: no se ofrece para registrar nada nuevo. */
    val isArchived: Boolean get() = archivedAt != null

    /** Si es la ubicación con la que nace la base, que nunca se archiva. */
    val isInitial: Boolean get() = systemKey != null
}

/**
 * Lo que la persona escribe de una ubicación.
 *
 * La inicial nace sin nombre propio, así que [name] y [normalizedName] pueden faltar, pero solo en
 * ella, y faltan los dos o ninguno.
 */
data class LocationDetails(val name: String?, val normalizedName: String?, val kind: LocationKind)

/** Una ubicación por crear, vigente. Las que crea la persona nunca tienen clave inicial. */
data class NewLocation(val details: LocationDetails, val createdAt: Instant)

/**
 * Lo que la persona escribe en la hoja de una ubicación, tal cual, antes de revisarlo. En la
 * inicial, [name] en blanco conserva el nombre traducido.
 */
data class LocationDraft(val name: String, val kind: LocationKind)

/** Lo que impide guardar una ubicación tal como se escribió. */
enum class LocationInputError {
    /** El nombre queda vacío en una ubicación que no tiene nombre traducido al que volver. */
    NAME_EMPTY,

    /** El nombre pasa de 40 caracteres. */
    NAME_TOO_LONG,
}
