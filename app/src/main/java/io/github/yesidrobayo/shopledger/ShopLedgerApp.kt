package io.github.yesidrobayo.shopledger

import android.app.Application
import io.github.yesidrobayo.shopledger.di.AppContainer

/**
 * El proceso de la app. Crea el contenedor de dependencias una sola vez, en [onCreate].
 *
 * El sistema también arranca el proceso para un trabajo en segundo plano, sin ninguna pantalla:
 * por eso aquí no se abre la base ni se hace nada lento. La base se abre después, fuera del hilo
 * principal.
 *
 * El orden del arranque importa, y las piezas que faltan van antes de crear el contenedor: el modo
 * seguro tras varios fallos seguidos, el idioma elegido y la restauración pendiente, que debe
 * terminar antes de que nada abra la base.
 */
class ShopLedgerApp : Application() {
    /** Las dependencias de la app. Existe desde [onCreate] hasta que el proceso termina. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
