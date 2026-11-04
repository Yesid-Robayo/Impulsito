package io.github.yesidrobayo.shopledger.data.transaction

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * El proceso que se mata en las pruebas de cierre forzado. Vive solo en el APK de pruebas, en un
 * proceso propio, para que la prueba pueda matarlo sin matarse a sí misma.
 *
 * Abre la base por el camino de la app y escribe una venta con el runner real. Al llegar al punto
 * pedido, con la transacción todavía abierta, deja un archivo con su identificador de proceso y el
 * tamaño del `-wal`, y se queda esperando para siempre: la prueba lo mata ahí, sin aviso, como el
 * sistema mata una app sin memoria. Con [STOP_AFTER_COMMIT] espera después de confirmar.
 */
class WriterProcessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent): IBinder {
        val request = Request.from(intent)
        scope.launch {
            try {
                writeUntilStopped(request)
            } catch (failure: Throwable) {
                // Cualquier falla, también un Error: la prueba debe saber que este proceso murió
                // por un fallo propio y no por el cierre que ella provoca. Solo la clase.
                request.ready.writeAtomically("error ${failure.javaClass.simpleName}")
                throw failure
            }
        }
        return Binder()
    }

    private suspend fun writeUntilStopped(request: Request) {
        val database = AppDatabase.open(this, request.database, Dispatchers.IO)
        if (request.cachePages > 0) {
            // Una caché chica obliga a SQLite a volcar al -wal páginas aún sin confirmar.
            database.useWriterConnection {
                it.executeSQL("PRAGMA cache_size = ${request.cachePages}")
            }
        }
        val wal = File(request.database.path + "-wal")
        val walBefore = wal.length()

        suspend fun stopHere(): Nothing {
            request.ready.writeAtomically(
                "ready ${Process.myPid()} ${request.stop} $walBefore ${wal.length()}",
            )
            awaitCancellation()
        }

        val counter =
            BackupStateChangeCounter(database.backupStateDao()).let { real ->
                // Cuenta de verdad y se detiene después: la marca ya está escrita, sin confirmar.
                ChangeCounter { now ->
                    real.markChanged(now)
                    if (request.stop == STOP_AT_CHANGE_COUNT) stopHere()
                }
            }
        val runner = runnerFor(database, counter)
        TestSaleUseCase(runner, database, request.priceListId)(request.productIds) { point ->
            if (point.toString() == request.stop) stopHere()
        }
        if (request.stop == STOP_AFTER_COMMIT) stopHere()
    }

    private class Request(
        val database: File,
        val ready: File,
        val stop: String,
        val priceListId: Long,
        val productIds: List<Long>,
        val cachePages: Int,
    ) {
        companion object {
            fun from(intent: Intent) = Request(
                database = File(requireNotNull(intent.getStringExtra(EXTRA_DATABASE))),
                ready = File(requireNotNull(intent.getStringExtra(EXTRA_READY))),
                stop = requireNotNull(intent.getStringExtra(EXTRA_STOP)),
                priceListId = intent.getLongExtra(EXTRA_PRICE_LIST, 0),
                productIds = requireNotNull(intent.getLongArrayExtra(EXTRA_PRODUCTS)).toList(),
                cachePages = intent.getIntExtra(EXTRA_CACHE_PAGES, 0),
            )
        }
    }

    companion object {
        const val EXTRA_DATABASE = "database"
        const val EXTRA_READY = "ready"
        const val EXTRA_STOP = "stop"
        const val EXTRA_PRICE_LIST = "priceList"
        const val EXTRA_PRODUCTS = "products"
        const val EXTRA_CACHE_PAGES = "cachePages"

        /** Se detiene al contar el cambio: lo último antes de confirmar. */
        const val STOP_AT_CHANGE_COUNT = "CHANGE_COUNT"

        /** Se detiene con la venta ya confirmada. */
        const val STOP_AFTER_COMMIT = "AFTER_COMMIT"
    }
}

/** Escribe y renombra: quien espera el archivo nunca lo lee a medias. */
internal fun File.writeAtomically(text: String) {
    val partial = File(path + ".tmp")
    partial.writeText(text)
    check(partial.renameTo(this)) { "No se pudo dejar el archivo listo" }
}
