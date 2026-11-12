package io.github.yesidrobayo.shopledger.data.safety

import java.io.File

/** Un arranque que empezó y todavía no se confirmó como bueno. */
internal data class PendingAttempt(val pid: Int, val startedAtMillis: Long)

/**
 * Lo que decidió el primer paso del conteo, solo con números y estados.
 *
 * @property storedAttempts intentos sin confirmar que había en el archivo.
 * @property countedAttempts los que siguen contando tras el contraste con el motivo de salida.
 * @property discardedAttempts los que el sistema mostró como un cierre que no fue un fallo.
 */
data class BootReport(
    val safeMode: Boolean,
    val storedAttempts: Int,
    val countedAttempts: Int,
    val discardedAttempts: Int,
)

/**
 * Conteo de arranques fallidos que decide el modo seguro, sin Room y antes de todo lo demás.
 *
 * Sigue cuatro pasos. El primero, en `Application.attachBaseContext`, lee y, con tres fallos
 * seguidos o más, entra en modo seguro; si no, suma un intento. Corre ahí, y no en `onCreate`,
 * para que también cuente una caída en `attachBaseContext` o en el inicializador de una biblioteca,
 * que Android ejecuta antes de `onCreate`. El segundo, en la última línea de
 * `Application.onCreate`, lo resta: un proceso que arrancó para un trabajo en segundo plano nunca
 * cuenta. El tercero, en la primera línea de `MainActivity.onCreate`, suma otra vez. El cuarto,
 * tras diez segundos estables o al dejar de verse la pantalla, vuelve a cero: abrir y cerrar
 * deprisa no es un fallo.
 *
 * Cada línea del archivo es un intento sin confirmar, con su pid y su hora de inicio. Guardar el
 * pid, en vez de un número suelto, deja contrastar cada intento con el motivo de salida de ese
 * mismo proceso: un contador solo diría que algún proceso no terminó, no cuál, y un cierre forzado
 * reciente podría borrar una caída anterior. El archivo solo lleva números.
 *
 * Todas las llamadas ocurren en el hilo principal de un único proceso, así que el estado en memoria
 * es la verdad y el archivo solo lo conserva para el siguiente proceso. Ningún fallo de disco
 * detiene el arranque: sin disco el conteo no avanza, y la app abre normal.
 */
internal class BootAttemptStore(
    noBackupDir: File,
    private val exitReasons: ExitReasonSource,
    private val currentPid: Int,
    private val clock: () -> Long,
) {
    private val file = File(noBackupDir, FILE_NAME)
    private var pending: List<PendingAttempt> = emptyList()

    /** Verdadero si este proceso abrió en modo seguro. Solo `resetForRetry` lo apaga. */
    var isSafeMode: Boolean = false
        private set

    /** Primer paso: lee, contrasta con el motivo de salida y decide. Sin modo seguro, suma 1. */
    fun onApplicationStart(): BootReport {
        val stored = read()
        val counted = countFailures(stored)
        isSafeMode = counted.size >= SAFE_MODE_THRESHOLD
        // En modo seguro no se suma: abrir la pantalla de rescate no es otro fallo.
        pending = if (isSafeMode) counted else counted + PendingAttempt(currentPid, clock())
        if (pending != stored) write(pending)
        return BootReport(
            safeMode = isSafeMode,
            storedAttempts = stored.size,
            countedAttempts = counted.size,
            discardedAttempts = stored.size - counted.size,
        )
    }

    /** Segundo paso: el arranque del proceso terminó bien, así que su intento deja de contar. */
    fun onApplicationCreated() {
        if (isSafeMode) return
        val index = pending.indexOfLast { it.pid == currentPid }
        if (index < 0) return
        pending = pending.filterIndexed { position, _ -> position != index }
        write(pending)
    }

    /** Tercer paso: se abre la pantalla. Hasta quedar estable, una caída cuenta como fallo. */
    fun onActivityCreated() {
        if (isSafeMode) return
        pending = pending + PendingAttempt(currentPid, clock())
        write(pending)
    }

    /** Cuarto paso: diez segundos estables, o la pantalla dejó de verse. Vuelve a cero. */
    fun onStable() {
        // En modo seguro el conteo se queda: salir del rescate no prueba que la app abra.
        if (isSafeMode || pending.isEmpty()) return
        pending = emptyList()
        write(pending)
    }

    /** `Reintentar abrir`: la persona pide arrancar normal, por si fue un falso positivo. */
    fun resetForRetry() {
        isSafeMode = false
        pending = emptyList()
        write(pending)
    }

    private fun countFailures(stored: List<PendingAttempt>): List<PendingAttempt> {
        if (stored.isEmpty()) return stored
        // Solo consulta al sistema si quedó algo sin confirmar: un arranque normal no paga nada.
        val exits = exitReasons.recentExits()
        if (exits.isEmpty()) return stored
        return stored.filter { attempt ->
            // Mismo pid y muerte posterior al inicio: un pid reciclado no se confunde. Un mismo
            // proceso puede tener varias salidas: Android 17 registra otra al cerrar el aviso de
            // caídas repetidas. Por eso se miran todas, no solo la más reciente.
            val ownExits =
                exits.filter {
                    it.pid == attempt.pid && it.timestampMillis >= attempt.startedAtMillis
                }
            // Cuenta si alguna fue un fallo, aunque otra posterior diga cierre forzado. Sin
            // registro del sistema también cuenta: perder un ciclo de fallos es peor que mostrar
            // la pantalla de rescate de más, que siempre ofrece reintentar. Solo se descarta si
            // todas sus salidas fueron de otro motivo.
            ownExits.isEmpty() || ownExits.any { it.isFailure }
        }
    }

    private fun read(): List<PendingAttempt> = try {
        if (file.exists()) parse(file.readText(Charsets.UTF_8)) else emptyList()
    } catch (_: Exception) {
        // Un archivo que no se puede leer no detiene el arranque.
        emptyList()
    }

    private fun parse(text: String): List<PendingAttempt> = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split(' ')
            val pid = parts.getOrNull(0)?.toIntOrNull()
            val startedAt = parts.getOrNull(1)?.toLongOrNull()
            // Una línea rota cuenta como fallo: sin pid no hay forma de descartarla.
            if (pid != null && startedAt != null) {
                PendingAttempt(pid, startedAt)
            } else {
                PendingAttempt(0, 0)
            }
        }.toList()

    private fun write(attempts: List<PendingAttempt>) {
        try {
            val text = attempts.joinToString(separator = "") { "${it.pid} ${it.startedAtMillis}\n" }
            writeAtomically(file, text)
        } catch (_: Exception) {
            // Sin disco el conteo no avanza. Nunca detiene el arranque.
        }
    }

    companion object {
        /** Nombre del archivo en `noBackupFilesDir`. */
        const val FILE_NAME = "boot_attempts"

        /** Fallos seguidos al abrir que llevan al modo seguro. */
        const val SAFE_MODE_THRESHOLD = 3

        /** Tiempo con la pantalla abierta tras el cual el arranque cuenta como bueno. */
        const val STABLE_AFTER_MILLIS = 10_000L
    }
}
