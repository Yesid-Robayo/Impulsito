package io.github.yesidrobayo.shopledger.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkerFactory
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.UserTextPort
import io.github.yesidrobayo.shopledger.demo.DemoMode
import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import io.github.yesidrobayo.shopledger.domain.port.failureHandler
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupStatusUseCase
import io.github.yesidrobayo.shopledger.lifecycle.AppLifecycleObserver
import io.github.yesidrobayo.shopledger.localization.AndroidLocalePlatform
import io.github.yesidrobayo.shopledger.localization.AndroidPaymentMethodNames
import io.github.yesidrobayo.shopledger.localization.AndroidUserTextPort
import io.github.yesidrobayo.shopledger.localization.AppLanguage
import io.github.yesidrobayo.shopledger.localization.LanguagePreferenceFile
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.localization.LocalizedSurface
import io.github.yesidrobayo.shopledger.localization.LocalizedSurfaces
import io.github.yesidrobayo.shopledger.localization.installedVersionOf
import io.github.yesidrobayo.shopledger.lock.AppLock
import io.github.yesidrobayo.shopledger.notification.AndroidBackupNotifier
import io.github.yesidrobayo.shopledger.notification.AndroidLowStockNotifier
import io.github.yesidrobayo.shopledger.notification.AndroidRecurringNotifier
import io.github.yesidrobayo.shopledger.notification.AndroidReminderNotifier
import io.github.yesidrobayo.shopledger.notification.AndroidRevenueLimitNotifier
import io.github.yesidrobayo.shopledger.notification.DailySummaryDelivery
import io.github.yesidrobayo.shopledger.notification.DailySummaryScheduler
import io.github.yesidrobayo.shopledger.notification.ExpiryNoticeDelivery
import io.github.yesidrobayo.shopledger.notification.LimitNoticeFile
import io.github.yesidrobayo.shopledger.notification.LowStockAlertDelivery
import io.github.yesidrobayo.shopledger.notification.NotificationChannels
import io.github.yesidrobayo.shopledger.notification.RecurringReminderDelivery
import io.github.yesidrobayo.shopledger.notification.RevenueLimitNoticeDelivery
import io.github.yesidrobayo.shopledger.shortcut.AppShortcuts
import io.github.yesidrobayo.shopledger.surface.LockStateWatcher
import io.github.yesidrobayo.shopledger.tile.NewSaleTileSurface
import io.github.yesidrobayo.shopledger.ui.backup.NoDestinationNoticeFile
import io.github.yesidrobayo.shopledger.ui.backup.NoDestinationNoticeStore
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import io.github.yesidrobayo.shopledger.ui.media.PhotoCache
import io.github.yesidrobayo.shopledger.ui.phrase.VoiceInputPreference
import io.github.yesidrobayo.shopledger.widget.QuickSaleWidgetUpdater
import java.io.File
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

    /**
     * La capa de datos. Su base todavía no está abierta. Las notificaciones del respaldo, que se
     * declaran más abajo, se piden al lanzar una, cuando ya existen.
     *
     * Con la nube del teléfono, el motor solo escribe con la app a la vista: lo dice el ciclo de
     * vida del proceso, que pasa a detenido poco después de que se va la última pantalla.
     */
    val data: DataGraph =
        DataGraph(
            application,
            dispatchers.io,
            clock,
            notifier = { backupNotifier },
            appVisible = {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                    Lifecycle.State.STARTED,
                )
            },
        )

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

    /**
     * Cuándo se aceptó el aviso sin destino de Inicio, guardado fuera de la base: es de este
     * teléfono, no viaja en una copia y restaurar no lo cambia. Crearlo no toca el disco.
     */
    val noDestinationNotice: NoDestinationNoticeStore =
        NoDestinationNoticeFile({ application.noBackupFilesDir })

    /**
     * Si `Decir` abre el servicio de voz del teléfono en vez del teclado, guardado fuera de
     * la base: es de este teléfono, no viaja en una copia y restaurar no lo cambia. Crearlo no
     * toca el disco.
     */
    val voiceInput: VoiceInputPreference =
        VoiceInputPreference({ application.noBackupFilesDir }, dispatchers.io)

    /** El contexto en el idioma de la interfaz para lo que corre fuera de una actividad. */
    val localizedContext: LocalizedContextProvider =
        LocalizedContextProvider(application, language.language)

    /** Los textos de los trabajos en segundo plano de la capa de datos, en el idioma elegido. */
    val userText: UserTextPort = AndroidUserTextPort(localizedContext)

    /**
     * El nombre de los métodos de pago iniciales en el idioma de la interfaz, que una venta congela
     * al cobrar con uno que nadie renombró.
     */
    val paymentMethodNames: PaymentMethodNames =
        AndroidPaymentMethodNames { localizedContext.context().resources }

    /** Las notificaciones del respaldo, en el idioma de la interfaz. */
    val backupNotifier: AndroidBackupNotifier =
        AndroidBackupNotifier(application, localizedContext)

    /** Los avisos de vencimiento de los lotes, una vez por lote, mientras la base está abierta. */
    val expiryNoticeDelivery: ExpiryNoticeDelivery by lazy {
        ExpiryNoticeDelivery(
            scope = applicationScope,
            notifier =
                AndroidReminderNotifier(application, localizedContext) {
                    when (val access = data.open()) {
                        is DataAccess.Ready -> access.ports.settings.get()
                        is DataAccess.Rescue -> error("Sin base no hay lotes que avisar")
                    }
                },
            clock = clock,
            log = log,
        )
    }

    /**
     * El resumen del día: la actividad principal sigue su hora al abrir la base y programa, o
     * cancela, el trabajo del teléfono que lo muestra.
     */
    val dailySummaryDelivery: DailySummaryDelivery by lazy {
        DailySummaryDelivery(applicationScope, DailySummaryScheduler(application), clock, log)
    }

    /**
     * Los recordatorios de gastos recurrentes por confirmar, mientras la base está abierta. Solo
     * avisan: nunca registran el gasto.
     */
    val recurringReminderDelivery: RecurringReminderDelivery by lazy {
        RecurringReminderDelivery(
            scope = applicationScope,
            notifier =
                AndroidRecurringNotifier(application, localizedContext) {
                    when (val access = data.open()) {
                        is DataAccess.Ready -> access.ports.settings.get()
                        is DataAccess.Rescue -> error("Sin base no hay gastos que recordar")
                    }
                },
            clock = clock,
            log = log,
        )
    }

    /**
     * Los avisos de stock bajo pendientes, mientras la base está abierta. La actividad principal
     * empieza a seguirlos al abrir la base.
     */
    val lowStockAlertDelivery: LowStockAlertDelivery by lazy {
        LowStockAlertDelivery(
            scope = applicationScope,
            notifier =
                AndroidLowStockNotifier(application, localizedContext) {
                    when (val access = data.open()) {
                        is DataAccess.Ready -> access.ports.settings.get()
                        is DataAccess.Rescue -> error("Sin base no hay alertas que avisar")
                    }
                },
            log = log,
        )
    }

    /**
     * El aviso del límite de ingresos, una vez por nivel y por ventana, mientras la base está
     * abierta. El último aviso se guarda fuera de la base, en este teléfono.
     */
    val revenueLimitNoticeDelivery: RevenueLimitNoticeDelivery by lazy {
        RevenueLimitNoticeDelivery(
            scope = applicationScope,
            notifier =
                AndroidRevenueLimitNotifier(application, localizedContext) {
                    when (val access = data.open()) {
                        is DataAccess.Ready -> access.ports.settings.get()
                        is DataAccess.Rescue -> error("Sin base no hay límite que avisar")
                    }
                },
            store = LimitNoticeFile({ application.noBackupFilesDir }, dispatchers.io),
            clock = clock,
            log = log,
        )
    }

    /** Los textos que viven fuera de la app, como los nombres de los canales de avisos. */
    val localizedSurfaces: LocalizedSurfaces =
        LocalizedSurfaces(
            contexts = localizedContext,
            surfaces =
                listOf(
                    NotificationChannels(application, NotificationChannels.APP_CHANNELS),
                    // Se pide al refrescar, cuando ya existe: se declara más abajo.
                    object : LocalizedSurface {
                        override suspend fun refresh(texts: Context) =
                            quickSaleWidget.refresh(texts)
                    },
                    NewSaleTileSurface(application, log),
                    AppShortcuts(application, log),
                ),
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

    /**
     * El respaldo al abrir la app y al pasar a segundo plano. Al abrir, el estado del respaldo
     * decide con la base abierta: si la base no abre sin riesgo, no hay nada que encolar.
     */
    val lifecycleObserver: AppLifecycleObserver =
        AppLifecycleObserver(
            scope = applicationScope,
            appOpenUpload = {
                when (val access = data.open()) {
                    // Con datos de ejemplo, el estado que se lee es el de la base de ejemplo, que
                    // nunca se respalda: no decide ninguna subida. Lo pendiente de la base del
                    // negocio sigue en la biblioteca de trabajos.
                    is DataAccess.Ready ->
                        if (data.demo.isActive()) {
                            AppOpenUpload.NONE
                        } else {
                            ObserveBackupStatusUseCase(access.ports.backupStatus, clock)
                                .onAppOpen()
                        }

                    is DataAccess.Rescue -> AppOpenUpload.NONE
                }
            },
            onAppOpened = { data.backupScheduler.onAppOpened(it) },
            onAppBackgrounded = { data.backupScheduler.onAppBackgrounded() },
        )

    /**
     * El bloqueo de la app, uno por proceso. Mide el segundo plano con el reloj que solo avanza desde
     * que arrancó el teléfono: cambiar la hora no lo engaña.
     */
    val appLock: AppLock = AppLock { android.os.SystemClock.elapsedRealtime() }

    /**
     * El modo demostración: la base de ejemplo que usa la interfaz, aparte de la del negocio, con
     * sus nombres en el idioma de la interfaz. Crearlo no toca el disco.
     */
    val demoMode: DemoMode by lazy {
        DemoMode(data.demo, { localizedContext.context().resources }, paymentMethodNames)
    }

    /**
     * Las miniaturas de las fotos ya decodificadas, una caché para todas las pantallas: así volver a
     * una lista no lee otra vez los mismos archivos. Una foto que falta en el teléfono se ve
     * pendiente si todavía puede bajar del servicio WebDAV, y no disponible si no.
     */
    val photoCache: PhotoCache by lazy {
        PhotoCache(
            directory = { File(application.filesDir, StoredImage.FOLDER) },
            io = dispatchers.io,
            whenMissing = { sha256 ->
                if (data.photoMayArrive(sha256)) PhotoState.Pending else PhotoState.Unavailable
            },
        )
    }

    /**
     * Redibuja el widget de venta rápida cuando cambia lo que muestra. La actividad principal
     * empieza a seguir la base al abrirla; sin widgets puestos no hace nada.
     */
    val quickSaleWidget: QuickSaleWidgetUpdater by lazy {
        QuickSaleWidgetUpdater(application, applicationScope, clock, log)
    }

    /**
     * El receptor de pantalla: redibuja el widget al bloquear y al desbloquear, para que un monto
     * no quede a la vista con el teléfono bloqueado. Lo registra el proceso al arrancar.
     */
    val lockWatcher: LockStateWatcher by lazy {
        LockStateWatcher(applicationScope, log) { quickSaleWidget.refresh() }
    }
}
