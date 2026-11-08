package io.github.yesidrobayo.shopledger

import android.app.Activity
import android.content.Intent
import io.github.yesidrobayo.shopledger.harness.BackupHarnessActivity

/**
 * La entrada a las herramientas de depuración, solo en `debug`. El arnés del respaldo no se exporta,
 * así que `adb` no lo abre directo: se pide a la actividad principal con un extra, y ella lo abre
 * encima, como un componente propio.
 *
 * `adb shell am start -n <paquete>/io.github.yesidrobayo.shopledger.MainActivity --ez
 * open_backup_harness true`
 *
 * Release tiene su propia versión de este archivo, que no hace nada: ningún extra abre nada ahí.
 */
internal object DebugEntry {
    /** El extra que pide abrir el arnés del respaldo. */
    const val OPEN_BACKUP_HARNESS = "open_backup_harness"

    /** Abre el arnés encima de [activity] si [intent] lo pide. Devuelve si lo abrió. */
    fun openIfRequested(activity: Activity, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(OPEN_BACKUP_HARNESS, false) != true) return false
        activity.startActivity(Intent(activity, BackupHarnessActivity::class.java))
        return true
    }
}
