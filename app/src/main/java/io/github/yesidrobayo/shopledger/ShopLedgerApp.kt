package io.github.yesidrobayo.shopledger

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.StrictMode
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
 */
class ShopLedgerApp : Application() {
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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.language.applyAtProcessStart()
        syncLanguageWithSystem()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Desde Android 13, un idioma cambiado en los ajustes de Android con la app abierta llega
        // por aquí.
        syncLanguageWithSystem()
    }

    // Desde Android 13 lo que diga Android manda, y se copia al archivo de idioma fuera del hilo
    // principal. Antes no hace nada.
    private fun syncLanguageWithSystem() {
        container.applicationScope.launch { container.language.syncWithSystem() }
    }
}
