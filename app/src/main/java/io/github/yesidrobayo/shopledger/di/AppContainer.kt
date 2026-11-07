package io.github.yesidrobayo.shopledger.di

import android.app.Application
import androidx.work.WorkerFactory
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.UserTextPort
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import io.github.yesidrobayo.shopledger.domain.port.failureHandler
import io.github.yesidrobayo.shopledger.localization.AndroidLocalePlatform
import io.github.yesidrobayo.shopledger.localization.AndroidUserTextPort
import io.github.yesidrobayo.shopledger.localization.AppLanguage
import io.github.yesidrobayo.shopledger.localization.LanguagePreferenceFile
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.localization.LocalizedSurfaces
import io.github.yesidrobayo.shopledger.localization.installedVersionOf
import io.github.yesidrobayo.shopledger.notification.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Las dependencias de la app, armadas a mano: cada pieza recibe lo que necesita por su constructor,
 * sin bibliotecas de inyección ni objetos globales.
 *
 * Lo crea [io.github.yesidrobayo.shopledger.ShopLedgerApp] una sola vez por proceso, y vive lo
 * mismo que él. Crearlo es barato y no toca el disco: la base se abre fuera del hilo principal,
 * con [DataGraph.open] cuando la pide la primera pantalla, o con [DataGraph.openForWork] cuando la
 * pide un trabajo en segundo plano, que nunca migra. Las pantallas reciben sus casos de uso desde
 * aquí, y nunca las clases de `:data`.
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
     * Crea los trabajos en segundo plano de la capa de datos, como el respaldo. Se arma la primera
     * vez que la biblioteca de trabajos lo pide, no al arrancar el proceso.
     */
    val workerFactory: WorkerFactory get() = data.workerFactory

    /**
     * El idioma de la interfaz, guardado fuera de la base. Crearlo no toca el disco: el proceso lo
     * aplica después, antes de la primera actividad.
     *
     * Elegir uno vuelve a escribir en ese idioma los textos de fuera de la app, con
     * [localizedSurfaces], que se declara más abajo: solo se usa al elegir, cuando ya existe.
     */
    val language: AppLanguage =
        AppLanguage(
            file = LanguagePreferenceFile({ application.noBackupFilesDir }),
            platform = AndroidLocalePlatform(application),
            log = log,
            ioContext = dispatchers.io,
            mainContext = dispatchers.main,
            afterChoice = { localizedSurfaces.refreshLocalizedSurfaces() },
        )

    /** El contexto en el idioma de la interfaz para lo que corre fuera de una actividad. */
    val localizedContext: LocalizedContextProvider =
        LocalizedContextProvider(application, language.language)

    /** Los textos de los trabajos en segundo plano de la capa de datos, en el idioma elegido. */
    val userText: UserTextPort = AndroidUserTextPort(localizedContext)

    /** Los textos que viven fuera de la app, como los nombres de los canales de avisos. */
    val localizedSurfaces: LocalizedSurfaces =
        LocalizedSurfaces(
            contexts = localizedContext,
            surfaces = listOf(NotificationChannels(application, NotificationChannels.APP_CHANNELS)),
            directory = { application.noBackupFilesDir },
            installedVersion = { installedVersionOf(application) },
            log = log,
            ioContext = dispatchers.io,
        )

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
