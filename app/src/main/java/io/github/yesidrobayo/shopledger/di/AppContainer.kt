package io.github.yesidrobayo.shopledger.di

import android.app.Application
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import io.github.yesidrobayo.shopledger.domain.port.failureHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Las dependencias de la app, armadas a mano: cada pieza recibe lo que necesita por su constructor,
 * sin bibliotecas de inyección ni objetos globales.
 *
 * Lo crea [io.github.yesidrobayo.shopledger.ShopLedgerApp] una sola vez por proceso, y vive lo
 * mismo que él. Crearlo es barato y no toca el disco: la base se abre con [DataGraph.open], fuera
 * del hilo principal, cuando la primera pantalla o el primer trabajo la piden. Las pantallas
 * reciben sus casos de uso desde aquí, y nunca las clases de `:data`.
 *
 * Las pruebas no lo usan: construyen cada clase con dobles.
 */
class AppContainer(application: Application) {
    val dispatchers =
        AppDispatchers(Dispatchers.Main.immediate, Dispatchers.IO, Dispatchers.Default)

    /** El reloj del teléfono. La zona de la fecha de negocio sale de los ajustes, nunca de aquí. */
    val clock: Clock = SystemClock

    /** La capa de datos. Su base todavía no está abierta. */
    val data: DataGraph = DataGraph(application, dispatchers.io, clock)

    /** El único punto de registro de la app. */
    val log: EventLog = data.log

    /**
     * Para el trabajo corto que sigue a una confirmación, como programar la copia. Lo que deba
     * sobrevivir a la app va en un trabajo en segundo plano del sistema.
     *
     * Una falla de un trabajo no detiene a los demás, y se registra solo por su clase: si llegara
     * al manejador del sistema, este escribiría su mensaje y su traza en el registro.
     */
    val applicationScope =
        CoroutineScope(
            SupervisorJob() + dispatchers.default +
                log.failureHandler(AppEvent.BACKGROUND_WORK_FAILED),
        )
}
