package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * El segundo destino automático: una carpeta local que recibe su propia copia, con o sin destino
 * conectado. Cuándo toca escribir y qué copias propias se podan.
 */
object SecondDestinationRule {
    /** Las copias más recientes que siempre se conservan. */
    const val KEEP_RECENT = 3

    /** Con `Mantener al día esta carpeta`, los días anteriores de los que se guarda la última. */
    const val KEEP_PREVIOUS_DAYS = 2

    /** Lo más que conserva la carpeta con la opción encendida. */
    const val MAX_FOLLOWING = KEEP_RECENT + KEEP_PREVIOUS_DAYS

    /**
     * Si toca la copia diaria: hubo cambios desde la última copia de la carpeta y pasó un día desde
     * ella. Un reloj que retrocedió cuenta como vencido: un reloj raro nunca deja sin copia.
     */
    fun dailyDue(changeSeq: Long, lastCopySeq: Long?, lastCopyAt: Instant?, now: Instant): Boolean {
        if (changeSeq == lastCopySeq) return false
        if (lastCopyAt == null) return true
        return now < lastCopyAt || now - lastCopyAt >= 1.days
    }

    /**
     * Con `Mantener al día esta carpeta`, si una racha ya lleva [BackupTimings.BACKUP_MAX_WAIT] sin
     * copia en la carpeta: como la subida, no espera a que la racha termine.
     */
    fun followMaxWaitReached(
        changeSeq: Long,
        lastCopySeq: Long?,
        lastCopyAt: Instant?,
        now: Instant,
    ): Boolean {
        if (changeSeq == lastCopySeq) return false
        if (lastCopyAt == null) return true
        return now < lastCopyAt || now - lastCopyAt >= BackupTimings.BACKUP_MAX_WAIT
    }

    /**
     * Las copias propias que se borran, de [copies] por su nombre y su instante. Se conservan las
     * [KEEP_RECENT] más recientes; con [following], además la última de cada uno de los
     * [KEEP_PREVIOUS_DAYS] días anteriores con copia, por día UTC, [MAX_FOLLOWING] como máximo. Lo
     * que no se borra, se conserva: nunca se toca otra cosa que [copies].
     */
    fun toPrune(copies: Map<String, CopyInstant>, following: Boolean): Set<String> {
        val newestFirst =
            copies.entries.sortedWith(
                compareByDescending<Map.Entry<String, CopyInstant>> { it.value }.thenBy { it.key },
            )
        val newestDay = newestFirst.firstOrNull()?.value?.retentionDay ?: return emptySet()
        val kept = newestFirst.take(KEEP_RECENT).map { it.key }.toMutableSet()
        if (following) {
            // Los días anteriores al de la copia más reciente, con alguna copia: la última de cada uno
            // de los dos más cercanos. Si ya está entre las recientes, no suma otra.
            newestFirst
                .filter { it.value.retentionDay < newestDay }
                .groupBy { it.value.retentionDay }
                .values
                .take(KEEP_PREVIOUS_DAYS)
                .forEach { sameDay -> kept += sameDay.first().key }
        }
        return copies.keys - kept
    }
}
