package io.github.yesidrobayo.shopledger.data.backup

import kotlinx.coroutines.sync.Mutex

/**
 * Detiene el respaldo mientras se prepara una restauración, y lo devuelve si se cancela.
 *
 * La preparación lee el destino para decidir la cabeza y si la copia ya está guardada: si una
 * subida terminara a la vez, la cabeza quedaría atrás y la copia siguiente vería un conflicto sin
 * motivo. Por eso, al pausar, ninguna ejecución del respaldo corre, y ninguna empieza hasta
 * reanudar. Si la preparación termina, el proceso se reinicia sin reanudar: el arranque lo retoma.
 */
internal interface BackupPause {
    /** Espera a que ninguna ejecución del respaldo corra, y no deja empezar ninguna. */
    suspend fun pause()

    /** Deja correr el respaldo otra vez. Sin pausa previa no hace nada. */
    fun resume()
}

/**
 * La pausa con el cerrojo del respaldo del proceso, el mismo que toma cada ejecución: tomarlo
 * espera a la que corre, y retenerlo deja esperando a la siguiente. Las que empiezan con el cerrojo
 * de la restauración ya tomado ven sus marcas y salen sin abrir la base.
 *
 * Antes de tomarlo cancela la espera y las dos subidas programadas, así una que corre se detiene en
 * su siguiente revisión en vez de terminar la copia, y una que esperaba no corre. Con el cerrojo ya
 * tomado comprueba con la biblioteca de trabajos que ninguno figura corriendo. Mientras dura la
 * pausa nada se encola, porque el cerrojo de la restauración ya está en el disco. Al reanudar, lo
 * pendiente se vuelve a encolar.
 *
 * @param work los trabajos programados del respaldo. Sin ellos, solo el cerrojo.
 */
internal class MutexBackupPause(
    private val mutex: Mutex,
    private val work: ScheduledBackupWork = ScheduledBackupWork.None,
) : BackupPause {
    override suspend fun pause() {
        work.cancel()
        mutex.lock(this)
        work.awaitNoneRunning()
    }

    override fun resume() {
        if (!mutex.holdsLock(this)) return
        mutex.unlock(this)
        work.resume()
    }
}

/** Lo que la pausa de una restauración hace con los trabajos programados del respaldo. */
internal interface ScheduledBackupWork {
    /** Cancela la espera y las dos subidas. */
    suspend fun cancel()

    /** Espera a que ninguno de los tres figure corriendo. */
    suspend fun awaitNoneRunning()

    /** Vuelve a encolar lo pendiente. No espera: corre aparte. */
    fun resume()

    companion object {
        /** Sin trabajos programados, como en las pruebas que solo miran el cerrojo. */
        val None: ScheduledBackupWork =
            object : ScheduledBackupWork {
                override suspend fun cancel() = Unit

                override suspend fun awaitNoneRunning() = Unit

                override fun resume() = Unit
            }
    }
}
