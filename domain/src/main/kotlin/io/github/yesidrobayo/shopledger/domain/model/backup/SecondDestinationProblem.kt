package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Por qué la última copia del segundo destino no quedó en la carpeta. Se muestra aparte, en `Copias
 * locales` y en `Salud de mis datos`, y nunca cambia el estado del respaldo ni el destino conectado.
 */
enum class SecondDestinationProblem {
    /** La carpeta no está: la tarjeta o la memoria puede estar fuera, o se borró. */
    FOLDER_MISSING,

    /** Se perdió el permiso sobre la carpeta: hay que elegirla de nuevo. */
    PERMISSION_LOST,

    /** No hubo espacio, en el teléfono para prepararla o en la carpeta. */
    NO_SPACE,

    /** No se pudo escribir o la relectura no coincidió. La carpeta quedó como estaba. */
    WRITE_FAILED,

    /** Con el cifrado activo, la clave no está disponible: no se escribe nada en claro. */
    KEY_UNAVAILABLE,

    /** La copia de hoy no se podría restaurar: no se escribió ni se podó nada. */
    FATAL_SKIPPED,
}
