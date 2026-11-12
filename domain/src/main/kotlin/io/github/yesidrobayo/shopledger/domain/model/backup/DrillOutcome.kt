package io.github.yesidrobayo.shopledger.domain.model.backup

/** Cómo terminó `Probar restauración`, para decirlo en la pantalla. */
enum class DrillOutcome {
    /** La copia más reciente se bajó entera, se validó y se migró: se puede restaurar. */
    PASSED,

    /** La copia no se pudo comprobar: queda marcada, y sale una copia nueva. */
    FAILED,

    /** No hubo espacio en el teléfono para probarla: no dice nada de la copia. */
    SKIPPED,

    /** Todavía no hay una copia que probar, o no hay un destino conectado. */
    NO_COPY,

    /** El destino no respondió, o una restauración está a medias: se puede repetir después. */
    UNREACHABLE,
}
