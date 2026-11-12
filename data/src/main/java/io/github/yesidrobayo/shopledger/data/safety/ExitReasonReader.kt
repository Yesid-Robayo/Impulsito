package io.github.yesidrobayo.shopledger.data.safety

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Lee los motivos de salida que el sistema guarda desde Android 11. En versiones anteriores
 * devuelve una lista vacía, y el conteo de arranques queda sin contraste: cuenta cada intento sin
 * confirmar.
 *
 * Usa el contexto tal como llega, sin pedir `applicationContext`: el primer paso del conteo corre
 * en `Application.attachBaseContext`, donde ese contexto todavía es nulo. El contexto base basta
 * para pedir un servicio del sistema y el nombre del paquete.
 */
internal class ExitReasonReader(private val context: Context) : ExitReasonSource {
    override fun recentExits(): List<ProcessExit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            readExits()
        } catch (_: RuntimeException) {
            // Sin motivos, el conteo sigue por el lado prudente: cuenta todos los intentos.
            emptyList()
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun readExits(): List<ProcessExit> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager
            .getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            .map { it.toProcessExit() }
    }

    private companion object {
        // Basta para cubrir tres intentos fallidos aunque entre ellos haya otros cierres, y las
        // salidas repetidas de un mismo proceso que registra Android 17.
        const val MAX_RECORDS = 16
    }
}

@TargetApi(Build.VERSION_CODES.R)
private fun ApplicationExitInfo.toProcessExit(): ProcessExit = ProcessExit(
    pid = pid,
    timestampMillis = timestamp,
    kind = exitKindOf(reason),
    reason = reason,
    status = status,
    importance = importance,
)
