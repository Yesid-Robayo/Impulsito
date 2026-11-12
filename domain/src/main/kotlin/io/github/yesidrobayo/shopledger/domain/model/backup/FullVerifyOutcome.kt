package io.github.yesidrobayo.shopledger.domain.model.backup

/** Cómo terminó `Comprobar ahora` en `Salud de mis datos`. */
enum class FullVerifyOutcome {
    /** La copia entera coincide con la suma de sus metadatos: queda anotada la lectura completa. */
    MATCHED,

    /** No coincide: queda marcada como dañada, a la vista, y sale una copia nueva. */
    MISMATCH,

    /** No hubo espacio en el teléfono para bajarla: no dice nada de la copia. */
    NO_SPACE,

    /** No hay una copia que comprobar, o el destino ya no es el de la lectura. */
    NO_COPY,

    /** El destino no respondió: se puede repetir después. */
    UNREACHABLE,
}
