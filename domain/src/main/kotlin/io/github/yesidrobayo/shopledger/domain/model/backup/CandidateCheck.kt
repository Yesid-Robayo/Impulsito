package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Qué hace el validador de copias con un hallazgo, según su clase.
 *
 * La misma clase rige al crear una copia y al restaurarla, porque las dos usan el mismo validador:
 * una copia que la app no pudiera restaurar tiene que notarse al crearla, no el día que hace falta.
 */
enum class FindingSeverity {
    /**
     * Un ataque o un daño estructural. Al restaurar, rechaza la copia. Al crearla, la copia se
     * guarda solo para un rescate manual, y no cuenta como copia restaurable.
     */
    FATAL,

    /**
     * Un saldo de cabecera que no coincide con el libro. No rechaza: avisa con el número de
     * productos afectados, pide confirmación y recalcula esos saldos desde el libro y las líneas.
     * El libro nunca se edita.
     */
    REPAIRABLE,

    /**
     * Un dato raro que no se puede derivar de nada. No rechaza ni recalcula: se cuenta y se dice al
     * confirmar. Si rechazara, un dato así dejaría sin restaurar todas las copias; y si marcara la
     * copia como dudosa, sería una alarma permanente que la persona no puede resolver y que
     * impediría podar.
     */
    WARNING,
}

/**
 * La lista cerrada de comprobaciones que el validador hace sobre una base candidata, más allá de la
 * revisión de integridad de SQLite, que valida la estructura pero no el significado. Cada una tiene
 * una clase fija ([severity]).
 *
 * Ninguna compara datos con listas de países, monedas o zonas: una copia nunca se rechaza por un
 * supuesto regional.
 */
enum class CandidateCheck(
    /** Qué hace el validador si esta comprobación falla. */
    val severity: FindingSeverity,
    /**
     * Si solo se puede comprobar con los metadatos de la copia en el destino. No se comprueba al
     * crear una copia, ni al restaurar desde un archivo elegido por la persona o al deshacer una
     * restauración, porque ahí no hay metadatos con qué comparar.
     */
    val requiresCopyMetadata: Boolean = false,
) {
    /**
     * El identificador del esquema coincide con el de esa versión. Es una comprobación de versión,
     * no de origen.
     */
    SCHEMA_IDENTITY(FindingSeverity.FATAL),

    /** Ninguna llave foránea apunta a una fila que no existe. */
    FOREIGN_KEYS(FindingSeverity.FATAL),

    /**
     * Toda columna de dinero y de cantidad guarda un entero: un texto rompería la lectura o las
     * cuentas.
     */
    STORAGE_TYPES(FindingSeverity.FATAL),

    /**
     * Los tipos del libro y de los documentos están en su lista cerrada. Un valor desconocido con la
     * misma versión de esquema es daño. Ninguna de esas listas lleva valores de un país, una marca o
     * una moneda.
     */
    LEDGER_ENUMS(FindingSeverity.FATAL),

    /**
     * Montos y cantidades dentro de los topes que protegen de desbordar, que no suponen una moneda;
     * instantes positivos, fechas de negocio válidas y textos dentro del largo de su campo. No deriva
     * de nada: el valor se conserva.
     */
    VALUE_RANGES(FindingSeverity.WARNING),

    /** El stock de cada producto es la suma de sus movimientos. */
    STOCK_ON_HAND(FindingSeverity.REPAIRABLE),

    /**
     * Teléfono, generación y versión de esquema de la base coinciden con los metadatos de la copia.
     */
    COPY_METADATA_MATCH(FindingSeverity.FATAL, requiresCopyMetadata = true),

    /** Una base vacía no reemplaza una base con datos sin una decisión expresa de la persona. */
    EMPTY_OVER_DATA(FindingSeverity.FATAL),

    /**
     * Costo promedio, valor del stock y totales de los documentos coinciden con el libro y las
     * líneas.
     */
    COST_AND_TOTALS(FindingSeverity.REPAIRABLE),

    /**
     * Datos abiertos, que nunca se comparan con una lista de países, monedas o zonas. Un tipo de
     * método de pago desconocido se trata como "otro": los métodos de pago son datos. De la moneda,
     * el formato regional, la zona del negocio y los idiomas solo se revisa la forma.
     */
    OPEN_DATA(FindingSeverity.WARNING),

    /**
     * Saldos guardados de stock, clientes, proveedores, cuentas de dinero y puntos coinciden con su
     * libro.
     */
    HEADER_BALANCES(FindingSeverity.REPAIRABLE),

    /**
     * Coherencia que no se puede derivar del libro, porque el libro no se reescribe: por ejemplo, una
     * cadena de saldos posteriores que no encadena.
     */
    UNDERIVABLE_COHERENCE(FindingSeverity.WARNING),

    /**
     * Reglas propias de cada tabla, como un factor de unidad positivo o una suma hexadecimal. Que
     * falte el archivo de una foto no es un hallazgo.
     */
    TABLE_RULES(FindingSeverity.WARNING),

    /**
     * Preferencias de presentación, como la paleta de colores. Un color nunca deja sin restaurar
     * una copia.
     */
    PRESENTATION_PREFERENCES(FindingSeverity.WARNING),
    ;

    /** Si esta comprobación corre en un camino que tiene, o no, los metadatos de la copia. */
    fun appliesWith(hasCopyMetadata: Boolean): Boolean = hasCopyMetadata || !requiresCopyMetadata
}

/**
 * Un hallazgo del validador: la comprobación que falló y en cuántos casos.
 *
 * [cases] cuenta filas, productos o valores, según la comprobación: es lo que se muestra al
 * confirmar una restauración. Nunca lleva el dato que falló, porque un hallazgo se puede registrar,
 * y un registro no lleva datos del negocio.
 */
data class CandidateFinding(val check: CandidateCheck, val cases: Long) {
    init {
        require(cases > 0) { "Un hallazgo cuenta al menos un caso" }
    }

    /** La clase del hallazgo, que fija su comprobación. */
    val severity: FindingSeverity get() = check.severity
}
