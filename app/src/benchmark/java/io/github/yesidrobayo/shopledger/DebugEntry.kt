package io.github.yesidrobayo.shopledger

import android.app.Activity
import android.content.Intent

/**
 * La compilación que miden las pruebas de rendimiento es la de release, firmada con la clave de
 * debug: tampoco trae herramientas de depuración, y ningún extra abre nada. Igual que la versión de
 * release de este archivo.
 */
internal object DebugEntry {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun openIfRequested(activity: Activity, intent: Intent?): Boolean = false
}
