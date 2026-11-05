package io.github.yesidrobayo.shopledger.data

import android.content.Context
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.log.AndroidEventLog
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La entrada pública de la capa de datos: lo único de `:data` que la app construye.
 *
 * Crearlo no toca el disco ni abre la base, así que se puede crear al arrancar el proceso, también
 * cuando el sistema lo arranca solo para un trabajo en segundo plano. La base se abre con [open],
 * fuera del hilo principal, y entrega los puertos de `:domain` ya conectados. Las clases de Room
 * quedan dentro de este módulo.
 *
 * **Dónde entra la compuerta del arranque.** [open] es el único camino hacia la base. Hoy la abre
 * sin más preguntas, lo que vale para una base nueva o para la que ya existe. La compuerta que
 * llegará después va aquí, antes de [AppDatabase.open]: si la app ya creó la base alguna vez y el
 * archivo falta, Room no se abre, porque crearía una base vacía encima del negocio, y [open]
 * devuelve un estado de rescate en vez de los puertos. También aquí esperarán la copia previa y la
 * migración. Nada fuera de esta clase abre la base, así que la compuerta no tiene otro camino que
 * cerrar.
 *
 * Una sola instancia por proceso: la base abierta vive en ella.
 *
 * @param ioContext contexto de las lecturas y escrituras del disco, fuera del hilo principal.
 * @param databaseFile da el archivo de la base. Se pide al abrir, en [ioContext], y no al crear esta
 *   clase: pedir la carpeta de bases toca el disco, porque Android la crea si falta, y crear esta
 *   clase ocurre en el hilo principal al arrancar el proceso.
 */
class DataGraph internal constructor(
    private val context: Context,
    private val ioContext: CoroutineContext,
    private val clock: Clock,
    private val databaseFile: () -> File,
    /** El único punto de registro de la app. */
    val log: EventLog,
) {
    private val mutex = Mutex()

    @Volatile private var ready: DataAccess.Ready? = null

    /** La capa de datos de la app, con su base en la carpeta de bases y el registro de Android. */
    constructor(context: Context, ioContext: CoroutineContext, clock: Clock) : this(
        context.applicationContext,
        ioContext,
        clock,
        { DatabaseFiles.databaseFile(context) },
        AndroidEventLog(),
    )

    /**
     * Abre la base, una sola vez por proceso, y entrega sus puertos.
     *
     * - **Fuera del hilo principal.** Abrir lee el disco y puede crear el esquema: corre en
     *   [ioContext], y se puede llamar desde el hilo principal sin bloquearlo.
     * - **Una sola vez.** Las llamadas a la vez esperan la misma apertura, y las siguientes reciben
     *   los mismos puertos.
     * - **Sin cortes.** Si quien la pidió se cancela, por ejemplo una pantalla que se cierra, la
     *   apertura termina igual y queda lista para la siguiente llamada.
     * - **Una base que no abre no se toca.** Devuelve [DataAccess.Unavailable], con la clase de la
     *   falla en el registro y nunca su mensaje. La llamada siguiente lo intenta de nuevo.
     */
    suspend fun open(): DataAccess {
        ready?.let { return it }
        return mutex.withLock {
            ready ?: withContext(ioContext + NonCancellable) { openDatabase() }
        }
    }

    private suspend fun openDatabase(): DataAccess {
        val started = TimeSource.Monotonic.markNow()
        // La ruta se pide aquí, ya en el contexto del disco.
        val (file, database) =
            try {
                val path = databaseFile()
                path to AppDatabase.open(context, path, ioContext, clock)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.error(DataEvent.DATABASE_OPEN_FAILED, failure)
                return DataAccess.Unavailable
            }
        val opened = DataAccess.Ready(DataPorts(database, file, ioContext, clock))
        ready = opened
        log.info(
            DataEvent.DATABASE_OPENED,
            LogField.durationMillis("duración", started.elapsedNow().inWholeMilliseconds),
        )
        return opened
    }
}
