package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withContext

/**
 * El cerrojo de una restauración: el archivo `restore/in_progress`, que la preparación crea antes de
 * tocar nada y retira al cancelar. Mientras esté tomado, ninguna escritura entra en la base: una
 * venta escrita entre la copia de seguridad y el reemplazo se perdería sin aviso.
 *
 * - **También con el diario.** Si existe `restore/journal.json`, el reemplazo ya empezó, y el cerrojo
 *   sigue tomado aunque falte el archivo: hasta que el arranque termine, nada escribe.
 * - **Nunca para siempre.** Sin diario, un cerrojo de más de una hora se da por abandonado y se
 *   borra: cubre una preparación colgada, que de otro modo dejaría la app sin vender. Con el reloj
 *   atrasado, una edad negativa, también cuenta como vencido. Al arrancar un proceso nuevo, el
 *   arranque lo borra sin mirar su edad, porque la preparación no sobrevive al proceso.
 * - **Con su instante dentro.** El archivo guarda cuándo se tomó, en milisegundos UTC. Si no se lee,
 *   cuenta la fecha del archivo. La preparación vuelve a mirarlo antes de escribir el diario: si ya
 *   no es el suyo, no sigue.
 *
 * Lee el disco en [ioContext].
 */
internal class RestoreLockFile(
    private val files: RestoreFiles,
    private val ops: DurableFileOps,
    private val clock: Clock,
    private val ioContext: CoroutineContext,
) : RestoreLock {
    override suspend fun isHeld(): Boolean = withContext(ioContext) {
        if (files.journal.exists()) return@withContext true
        val takenAt = takenAtMillis() ?: return@withContext false
        if (isLive(takenAt)) {
            true
        } else {
            // Abandonado: nadie lo va a retirar, y retenerlo dejaría la app sin vender.
            release()
            false
        }
    }

    /**
     * Toma el cerrojo con el instante actual. Devuelve ese instante, que identifica a quien lo tomó,
     * o `null` si ya estaba tomado y vigente, o si hay un diario.
     */
    suspend fun acquire(): Long? = withContext(ioContext) {
        if (files.journal.exists()) return@withContext null
        val current = takenAtMillis()
        if (current != null && isLive(current)) return@withContext null
        val now = clock.nowEpochMillis()
        ops.writeAtomically(
            files.lock,
            files.lockTemporary,
            now.toString().toByteArray(Charsets.UTF_8),
        )
        now
    }

    /**
     * Si el cerrojo sigue siendo el que se tomó en [takenAt] y le queda al menos [margin] de vida.
     * La preparación lo pregunta antes de escribir el diario: con el margen, ninguna escritura
     * alcanza a ver el cerrojo vencido entre la pregunta y el diario.
     */
    suspend fun isOwned(takenAt: Long, margin: Duration = OWNER_MARGIN): Boolean =
        withContext(ioContext) {
            if (takenAtMillis() != takenAt) return@withContext false
            val age = clock.nowEpochMillis() - takenAt
            age >= 0 && age < (LIFETIME - margin).inWholeMilliseconds
        }

    /** Retira el cerrojo y su temporal, si están. */
    suspend fun releaseNow(): Unit = withContext(ioContext) { release() }

    private fun release() {
        ops.delete(files.lockTemporary)
        ops.delete(files.lock)
    }

    private fun isLive(takenAt: Long): Boolean {
        val age = clock.nowEpochMillis() - takenAt
        return age >= 0 && age < LIFETIME.inWholeMilliseconds
    }

    /** El instante guardado en el cerrojo, o la fecha del archivo si no se lee. Nulo sin cerrojo. */
    private fun takenAtMillis(): Long? {
        val lock = files.lock
        if (!lock.isFile) return null
        val text =
            try {
                if (lock.length() > MAX_BYTES) null else lock.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                null
            }
        return text?.takeIf { DECIMAL.matches(it) }?.toLongOrNull() ?: lock.lastModified()
    }

    companion object {
        /** Cuánto dura el cerrojo sin diario antes de darse por abandonado. */
        val LIFETIME: Duration = 1.hours

        /**
         * Lo que debe quedarle al cerrojo para escribir el diario. Una preparación que se acerca al
         * vencimiento se detiene en vez de arriesgarse a que una venta entre a la vez.
         */
        val OWNER_MARGIN: Duration = 5.minutes

        private const val MAX_BYTES = 64L
        private val DECIMAL = Regex("0|[1-9][0-9]*")
    }
}
