package io.github.yesidrobayo.shopledger

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.StrictMode
import androidx.camera.core.CameraXConfig
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration as WorkConfiguration
import io.github.yesidrobayo.shopledger.data.safety.AppVersions
import io.github.yesidrobayo.shopledger.data.safety.BootGuard
import io.github.yesidrobayo.shopledger.di.AppContainer
import io.github.yesidrobayo.shopledger.ui.scanner.scannerCameraConfig
import kotlinx.coroutines.launch

/**
 * El proceso de la app. Crea el contenedor de dependencias una sola vez, en [onCreate].
 *
 * El sistema también arranca el proceso para un trabajo en segundo plano, sin ninguna pantalla:
 * por eso aquí no se abre la base ni se hace nada lento. La base se abre después, fuera del hilo
 * principal.
 *
 * El orden del arranque importa. Lo primero, en [attachBaseContext], es el conteo de arranques
 * fallidos: tras tres fallos seguidos al abrir, la app entra en modo seguro, muestra la pantalla de
 * rescate y no abre la base. Va ahí, y no en [onCreate], para que también cuente una caída de una
 * biblioteca que Android inicializa antes. La última línea de [onCreate] da por bueno el arranque
 * del proceso, así que uno que el sistema abre para un trabajo nunca cuenta. El idioma se aplica en
 * [onCreate], antes de la primera actividad, para que la primera pantalla ya salga en el idioma
 * elegido. La restauración pendiente la termina la compuerta de la base, antes de abrirla.
 *
 * Los textos que viven fuera de la app, como los nombres de los canales de avisos, se revisan al
 * arrancar y con cada cambio de configuración: un idioma cambiado con la app cerrada, o desde los
 * ajustes de Android, llega por uno de los dos caminos.
 *
 * La biblioteca de trabajos no arranca sola al abrir el proceso: la primera vez que se la pide, lee
 * aquí su configuración, con la fábrica de trabajos de la capa de datos. Eso pasa después de
 * [onCreate], cuando el contenedor ya existe.
 */
class ShopLedgerApp :
    Application(),
    WorkConfiguration.Provider,
    CameraXConfig.Provider {
    /** Las dependencias de la app. Existe desde [onCreate] hasta que el proceso termina. */
    lateinit var container: AppContainer
        private set

    /** El conteo de arranques fallidos y el registro de fallos. Existe desde [attachBaseContext]. */
    lateinit var bootGuard: BootGuard
        private set

    /** Si este proceso abrió en modo seguro: la pantalla de rescate, sin la base. */
    val safeMode: Boolean get() = bootGuard.safeMode

    /**
     * `Reintentar abrir`: vacía el conteo, para que el arranque siguiente abra normal, por si el
     * modo seguro fue un falso positivo. Quien llama reinicia la app después.
     */
    fun retryOpening() = withDiskPermit { bootGuard.retry() }

    /**
     * El registro local de fallos, para mostrarlo en el rescate antes de compartirlo. Lee el disco:
     * va fuera del hilo principal.
     */
    fun crashReport(): String = bootGuard.crashReport()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Paso 1 del conteo, antes que todo: lee, decide el modo seguro y, si no, anota un intento.
        // Instala también el registro de fallos, para que una caída desde aquí deje su archivo.
        // Todavía no hay contexto de la aplicación: solo se usan el contexto base y noBackupFilesDir.
        bootGuard = BootGuard.start(base) { appVersions(base) }
        // Solo en compilaciones depurables, y lo antes posible, para que también vea a las
        // bibliotecas que arrancan antes de onCreate: StrictMode anota en el registro cada lectura
        // o escritura del disco en el hilo principal, que demoraría el arranque o una pantalla.
        if ((base.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().setWorkerFactory(container.workerFactory).build()

    /**
     * La cámara del escáner, solo la trasera. La pide la cámara la primera vez que el escáner la
     * abre, nunca al arrancar.
     */
    override fun getCameraXConfig(): CameraXConfig = scannerCameraConfig()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.language.applyAtProcessStart()
        followLanguage()
        // Solo avisa cuando una pantalla se abre o la app entera pasa a segundo plano: un proceso
        // que el sistema arranca para un trabajo no lo dispara. En modo seguro no: abriría la base.
        if (!safeMode) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(container.lifecycleObserver)
        }
        // El bloqueo cuenta el tiempo en segundo plano de la app entera: rotar no es salir.
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appLock)
        // Paso 2, en la última línea: el arranque del proceso terminó bien.
        withDiskPermit { bootGuard.afterApplicationCreated() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Desde Android 13, el idioma elegido en la app o en los ajustes de Android llega por aquí
        // después de elegirlo, y en cualquier versión, el del teléfono cuando la app lo sigue.
        followLanguage()
    }

    // Fuera del hilo principal. Desde Android 13 lo que diga Android manda y se copia al archivo de
    // idioma; antes eso no hace nada. Después, los textos de fuera de la app se vuelven a escribir
    // solo si cambió el idioma de sus recursos o la versión instalada.
    private fun followLanguage() {
        container.applicationScope.launch {
            container.language.syncWithSystem()
            container.localizedSurfaces.refreshLocalizedSurfaces()
        }
    }
}

/**
 * Las versiones que acompañan un registro de fallo. Se piden al escribirlo, no al arrancar: es una
 * llamada al sistema.
 */
private fun appVersions(context: Context): AppVersions {
    val manager = context.packageManager
    val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
    return AppVersions(
        versionName = info.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(info),
        sdkInt = Build.VERSION.SDK_INT,
    )
}

/**
 * Corre [block] con permiso de leer y escribir el disco en el hilo principal. Es para los pasos del
 * conteo de arranques: archivos de unas pocas líneas, que tienen que estar escritos antes de seguir.
 */
internal fun <T> withDiskPermit(block: () -> T): T {
    // Permitir escribir incluye leer.
    val previous = StrictMode.allowThreadDiskWrites()
    try {
        return block()
    } finally {
        StrictMode.setThreadPolicy(previous)
    }
}
