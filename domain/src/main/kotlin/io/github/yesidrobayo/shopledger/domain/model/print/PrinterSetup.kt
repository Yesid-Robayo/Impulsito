package io.github.yesidrobayo.shopledger.domain.model.print

/**
 * Una impresora térmica guardada: una de las ya emparejadas en el teléfono, con el ancho de su
 * papel. [address] la identifica y nunca se registra; [name] es el que da el sistema, si lo da.
 */
data class SavedPrinter(val address: String, val name: String?, val paper: PaperWidth)

/**
 * Las impresoras guardadas en este teléfono y cuál se usa por defecto. Viven fuera de la base: son
 * de la instalación, no del negocio, y no viajan en una copia.
 *
 * Cada cambio devuelve una configuración nueva: la primera impresora queda por defecto, quitar la
 * de por defecto pasa ese lugar a la primera que quede, y nunca hay más de [MAX_PRINTERS].
 */
data class PrinterSetup(val printers: List<SavedPrinter>, val defaultAddress: String?) {
    /** La impresora por defecto, o `null` si no hay ninguna guardada. */
    val default: SavedPrinter?
        get() = printers.firstOrNull { it.address == defaultAddress } ?: printers.firstOrNull()

    /** Si ya no cabe otra impresora nueva. */
    val isFull: Boolean get() = printers.size >= MAX_PRINTERS

    /**
     * Con [printer] guardada: si ya estaba, con su nombre y papel nuevos y en su lugar; si no, al
     * final. La primera queda por defecto. Si ya hay [MAX_PRINTERS], una nueva no entra.
     */
    fun with(printer: SavedPrinter): PrinterSetup {
        val index = printers.indexOfFirst { it.address == printer.address }
        val updated = when {
            index >= 0 -> printers.toMutableList().apply { set(index, printer) }
            isFull -> return this
            else -> printers + printer
        }
        return PrinterSetup(updated, defaultAddress ?: printer.address)
    }

    /** Sin la impresora de [address]. Si era la de por defecto, pasa a la primera que quede. */
    fun without(address: String): PrinterSetup {
        val remaining = printers.filterNot { it.address == address }
        val default = defaultAddress.takeIf { it != address } ?: remaining.firstOrNull()?.address
        return PrinterSetup(remaining, default)
    }

    /** Con la impresora de [address] por defecto, si está guardada. */
    fun withDefault(address: String): PrinterSetup =
        if (printers.any { it.address == address }) copy(defaultAddress = address) else this

    /** Con [paper] para la impresora de [address], si está guardada. */
    fun withPaper(address: String, paper: PaperWidth): PrinterSetup = copy(
        printers = printers.map { if (it.address == address) it.copy(paper = paper) else it },
    )

    companion object {
        /** Sin impresoras: el comienzo, y lo que queda si el archivo no se puede leer. */
        val EMPTY: PrinterSetup = PrinterSetup(emptyList(), null)

        /**
         * Cuántas impresoras se guardan como máximo. Un negocio pequeño tiene una o dos: el tope
         * solo acota el archivo.
         */
        const val MAX_PRINTERS: Int = 8
    }
}
