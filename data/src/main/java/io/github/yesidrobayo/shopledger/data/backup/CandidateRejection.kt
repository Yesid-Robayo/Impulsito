package io.github.yesidrobayo.shopledger.data.backup

/**
 * Por qué se rechazó una copia antes de llegar a revisar sus datos, o al revisarlos.
 *
 * Es el único dato de un rechazo que puede ir al registro: un código fijo, sin rutas, nombres ni
 * nada de la copia. La pantalla elige su texto por este código, y nunca muestra detalles técnicos.
 * Todos dejan intacta la base activa: el rechazo ocurre antes de tocarla.
 */
internal enum class CandidateRejection {
    /** La suma SHA-256 de lo descargado no es la esperada: la copia cambió o llegó dañada. */
    CHECKSUM_MISMATCH,

    /** Los primeros bytes no son de ningún formato de copia que la app conozca. */
    UNKNOWN_FORMAT,

    /**
     * Una copia cifrada. Esta versión todavía no abre copias cifradas: no se dice que esté dañada,
     * porque no lo está.
     */
    ENCRYPTED_FORMAT,

    /**
     * Descomprimida pasaría del tope de la restauración. Una copia legítima nunca llega ahí: al
     * crearla, una que no cabe no cuenta como copia buena.
     */
    DECOMPRESSED_TOO_LARGE,

    /** El archivo comprimido está cortado o dañado: no se descomprime entero. */
    DAMAGED_ARCHIVE,

    /** Lo descomprimido no tiene el encabezado de una base de SQLite, o está cortado. */
    NOT_A_DATABASE,

    /**
     * La base viene en modo WAL. Una copia de la app siempre está en modo de diario normal, y
     * abrir una en WAL crearía archivos a su lado.
     */
    WAL_DATABASE,

    /** La versión del esquema es menor que 1: no es una base de la app. */
    UNKNOWN_SCHEMA_VERSION,

    /** La copia viene de una versión más nueva de la app. Se pide actualizar, nunca se dice dañada. */
    SCHEMA_TOO_NEW,

    /** La conexión aislada no quedó con las protecciones que exige abrir una copia ajena. */
    ISOLATION_FAILED,

    /**
     * El esquema no es exactamente el de su versión: una vista, una tabla o un índice de más, un
     * disparador ajeno, una guarda distinta o que falta, una tabla virtual o una expresión que el
     * esquema no tiene.
     */
    SCHEMA_MISMATCH,

    /** `integrity_check` encontró daño en la estructura del archivo. */
    INTEGRITY_FAILED,

    /** La revisión de los datos encontró un hallazgo fatal. */
    DATA_FATAL,

    /**
     * Tras recalcular los saldos de cabecera, la revisión siguió pidiendo recalcular: hay un saldo
     * que no sale del libro, y la copia no se puede dar por reparada.
     */
    REPAIR_INCOMPLETE,
}
