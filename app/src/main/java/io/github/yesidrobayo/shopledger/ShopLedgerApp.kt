package io.github.yesidrobayo.shopledger

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration as WorkConfiguration
import io.github.yesidrobayo.shopledger.di.AppContainer
import kotlinx.coroutines.launch

/**
 * El proceso de la app. Crea el contenedor de dependencias una sola vez, en [onCreate].
 *
 * El sistema también arranca el proceso para un trabajo en segundo plano, sin ninguna pantalla:
 * por eso aquí no se abre la base ni se hace nada lento. La base se abre después, fuera del hilo
 * principal.
 *
 * El orden del arranque importa. El idioma se aplica en [onCreate], antes de la primera actividad,
 * para que la primera pantalla ya salga en el idioma elegido. Las piezas que faltan van antes de
 * crear el contenedor: el modo seguro tras varios fallos seguidos y la restauración pendiente, que
 * debe terminar antes de que nada abra la base. El idioma no depende de ellas, porque no vive en la
 * base y restaurar no lo cambia.
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
    WorkConfiguration.Provider {
    /** Las dependencias de la app. Existe desde [onCreate] hasta que el proceso termina. */
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.language.applyAtProcessStart()
        followLanguage()
        // Solo avisa cuando una pantalla se abre o la app entera pasa a segundo plano: un proceso
        // que el sistema arranca para un trabajo no lo dispara.
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.lifecycleObserver)
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
