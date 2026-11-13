package io.github.yesidrobayo.shopledger.domain.rule.backup

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Cómo se ve en Inicio el aviso de que los datos solo están en este teléfono. */
enum class NoDestinationNotice {
    /** El aviso entero, con su frase, `Entendido` y `Elegir dónde guardar`. */
    FULL,

    /** Una línea pequeña que también lleva a elegir dónde guardar. */
    COMPACT,
}

/**
 * Cuándo se tocó `Entendido` en el aviso sin destino.
 *
 * @property acknowledgedAt el instante en que se tocó.
 * @property lastSaleId el identificador de la venta más reciente en ese momento, o 0 sin ventas.
 *   Los identificadores de venta solo crecen y ninguna venta se borra, así que la diferencia con
 *   la más reciente de hoy cuenta las ventas nuevas sin leer nada más de la base.
 */
data class NoDestinationAcknowledgement(val acknowledgedAt: Instant, val lastSaleId: Long)

/**
 * El aviso de Inicio cuando no hay dónde guardar las copias. Sin destino, perder el teléfono es
 * perder los datos: el aviso no se puede quitar, pero `Entendido` lo reduce a una línea, para que
 * no estorbe a quien ya lo leyó.
 *
 * Vuelve entero cuando pasaron [REMIND_AFTER] o se registraron [REMIND_AFTER_NEW_SALES] ventas
 * desde que se aceptó: lo que se perdería ya creció. También vuelve entero si algo no cuadra,
 * porque mostrar de más nunca pierde datos:
 *
 * - el reloj quedó antes del momento en que se aceptó;
 * - la venta más reciente es anterior a la de ese momento, como tras restaurar otra base.
 *
 * Elegir dónde guardar termina el ciclo: quien llama borra la aceptación.
 */
object NoDestinationNoticeRule {
    /** Cada cuánto vuelve entero, aunque no haya ventas nuevas. */
    val REMIND_AFTER: Duration = 7.days

    /** Cuántas ventas nuevas lo hacen volver entero antes de esos días. */
    const val REMIND_AFTER_NEW_SALES: Long = 50

    /**
     * Cómo se ve el aviso ahora.
     *
     * @param acknowledgement cuándo se aceptó, o `null` si nunca se aceptó o se perdió el
     *   registro.
     * @param lastSaleId el identificador de la venta más reciente hoy, o 0 sin ventas.
     */
    fun noticeOf(
        acknowledgement: NoDestinationAcknowledgement?,
        now: Instant,
        lastSaleId: Long,
    ): NoDestinationNotice {
        if (acknowledgement == null) return NoDestinationNotice.FULL
        val elapsed = now - acknowledgement.acknowledgedAt
        val newSales = lastSaleId - acknowledgement.lastSaleId
        val stillFresh =
            !elapsed.isNegative() &&
                elapsed < REMIND_AFTER &&
                newSales >= 0 &&
                newSales < REMIND_AFTER_NEW_SALES
        return if (stillFresh) NoDestinationNotice.COMPACT else NoDestinationNotice.FULL
    }
}
