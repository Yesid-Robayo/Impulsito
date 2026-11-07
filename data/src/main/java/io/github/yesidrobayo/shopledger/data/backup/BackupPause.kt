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
 * de la restauración ya tomado ven sus marcas y salen sin abrir la base. Una que ya esperaba solo
 * corre al reanudar, cuando la restauración se canceló, o nunca, porque el proceso se reinicia.
 *
 * Cancelar los trabajos programados y comprobar con la biblioteca de trabajos que ninguno corre
 * llega con la programación del respaldo, que hoy no existe: sin ella, ningún trabajo se encola
 * solo.
 */
internal class MutexBackupPause(private val mutex: Mutex) : BackupPause {
    override suspend fun pause() {
        mutex.lock(this)
    }

    override fun resume() {
        if (mutex.holdsLock(this)) mutex.unlock(this)
    }
}
