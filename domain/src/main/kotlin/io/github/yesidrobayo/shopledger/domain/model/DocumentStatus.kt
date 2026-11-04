package io.github.yesidrobayo.shopledger.domain.model

/**
 * El estado de una venta, una compra o un gasto. Es un valor guardado en la base: su nombre no
 * cambia nunca.
 *
 * Un documento confirmado no se edita ni se borra: nace activo y solo puede anularse, una vez. Así
 * el pasado queda como ocurrió, y corregir es anular y registrar otro.
 */
enum class DocumentStatus {
    /** Vigente: cuenta en saldos, ganancias y reportes. */
    ACTIVE,

    /** Anulado: se conserva para la historia, pero ya no cuenta. */
    VOIDED,
    ;

    companion object {
        /**
         * El estado que corresponde a [voiding]. Un documento está anulado si y solo si tiene datos
         * de anulación: por eso el estado se deduce de ellos y nunca se guarda por separado en un
         * modelo, donde podrían contradecirse.
         */
        fun of(voiding: Voiding?): DocumentStatus = if (voiding == null) ACTIVE else VOIDED
    }
}
