package io.github.yesidrobayo.shopledger

import android.app.Activity
import android.content.Intent

/**
 * Release no trae herramientas de depuración: ningún extra abre nada. La versión de `debug` abre el
 * arnés del respaldo.
 */
internal object DebugEntry {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun openIfRequested(activity: Activity, intent: Intent?): Boolean = false
}
