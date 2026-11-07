package io.github.yesidrobayo.shopledger.data

/**
 * Lo que dio [DataGraph.open] a la app en primer plano: los puertos, o el rescate.
 *
 * Es un resultado cerrado y no una excepción: quien lo recibe decide qué mostrar con un `when`, y
 * el compilador le avisa cuando aparezca un estado nuevo. La app en primer plano nunca espera una
 * migración, porque es ella quien la corre: por eso aquí no está [WorkAccess.MigrationPending].
 */
sealed interface DataAccess {
    /** La base abrió, y estos son sus puertos. */
    class Ready(val ports: DataPorts) :
        DataAccess,
        WorkAccess

    /**
     * La base no se puede abrir sin riesgo, y la app debe ir a la pantalla de rescate. Nada se
     * borró ni se reemplazó: el archivo, si está, quedó como estaba. La causa quedó registrada por
     * su nombre, y la de una falla, por su clase.
     *
     * No queda guardado: la llamada siguiente lo intenta de nuevo, que es lo que hace `Reintentar`.
     */
    data class Rescue(val cause: RescueCause) :
        DataAccess,
        WorkAccess
}

/**
 * Lo que dio [DataGraph.openForWork] a un trabajo en segundo plano: lo mismo que a la app, más la
 * espera de una migración.
 */
sealed interface WorkAccess {
    /**
     * La base es de una versión anterior de la app y debe migrar, y una migración solo corre con la
     * app en primer plano, donde una falla tiene pantalla. El trabajo no abrió la base: devuelve
     * `retry()` y el sistema lo vuelve a correr más tarde, cuando la app ya la migró.
     */
    data object MigrationPending : WorkAccess
}

/** Por qué la app va al rescate. Cada causa tiene su texto en la pantalla de rescate. */
sealed interface RescueCause {
    /**
     * La base ya había sido creada en este teléfono y su archivo falta, o está vacío. Room crearía
     * una base vacía encima del negocio, así que no se abrió.
     */
    data object DatabaseMissing : RescueCause

    /** La base viene de una versión más nueva de la app. Hay que actualizarla, y nada cambia. */
    data object NewerVersion : RescueCause

    /** La base no abrió: está dañada, no es una base de la app, o el disco falló al leerla. */
    data object OpenFailed : RescueCause

    /** Una migración falló. Room la revirtió entera, y la base sigue en su versión anterior. */
    data object MigrationFailed : RescueCause

    /**
     * No se pudo guardar la copia previa a migrar, así que no se migró: sin esa copia, un salto que
     * pierde filas sin fallar no tendría vuelta atrás.
     */
    data object PreMigrationCopyFailed : RescueCause

    /**
     * No cabe la copia previa a migrar, así que no se copió ni se migró, y la base quedó como
     * estaba. La pantalla de rescate dice cuánto falta y ofrece `Reintentar`.
     *
     * @property missingBytes cuánto hay que liberar, en bytes.
     */
    data class NotEnoughSpace(val missingBytes: Long) : RescueCause
}
