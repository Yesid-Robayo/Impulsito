package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant

/** El segundo destino tal como lo muestra `Copias locales`. Se lee sin red, del teléfono. */
sealed interface SecondDestinationView {
    /** No hay carpeta elegida. */
    data object None : SecondDestinationView

    /**
     * Su archivo existe y no se entiende: no se escribe ni se poda hasta elegir la carpeta otra
     * vez, y la pantalla lo dice como un permiso perdido.
     */
    data object Unreadable : SecondDestinationView

    /**
     * Una carpeta elegida.
     *
     * @property folderName el nombre de la carpeta, solo para mostrarlo; nulo si no se pudo leer.
     * @property lastCopyAt la última copia que quedó en ella, o nula.
     * @property followMainBackup `Mantener al día esta carpeta`.
     * @property problem por qué la última copia no quedó, o nulo.
     */
    data class Connected(
        val folderName: String?,
        val lastCopyAt: Instant?,
        val followMainBackup: Boolean,
        val problem: SecondDestinationProblem?,
    ) : SecondDestinationView
}

/** Cómo terminó elegir la carpeta del segundo destino. */
enum class ConnectFolderResult {
    /** Quedó elegida, con su permiso, y las copias empezarán solas. */
    CONNECTED,

    /**
     * No es del teléfono, de una tarjeta SD ni de una memoria USB: se rechaza sin guardar el
     * permiso, aunque se declare local.
     */
    NOT_LOCAL,

    /** No se pudo leer, crear la carpeta de la app o guardar. Nada quedó a medias. */
    FAILED,
}

/**
 * Una copia que dejó una instalación anterior en su carpeta local.
 *
 * @property documentUri el documento, para restaurarlo por el camino de `Buscar un archivo`.
 * @property createdAt el instante de su nombre.
 * @property generation la generación de su nombre.
 * @property encrypted si es una copia cifrada, que pide el código de recuperación.
 */
data class FoundLocalCopy(
    val documentUri: String,
    val createdAt: Instant,
    val generation: Long,
    val encrypted: Boolean,
)

/** Lo que se encontró en la carpeta elegida al reinstalar. */
sealed interface FoundLocalCopies {
    /** Las copias, de la más reciente a la más vieja. Nunca vacía. */
    data class Found(val copies: List<FoundLocalCopy>) : FoundLocalCopies

    /** La carpeta no tiene copias de la app. */
    data object NoCopies : FoundLocalCopies

    /** La carpeta no es del almacenamiento del teléfono: se rechaza sin guardar el permiso. */
    data object NotLocal : FoundLocalCopies

    /** No se pudo leer la carpeta. */
    data object Failed : FoundLocalCopies
}
