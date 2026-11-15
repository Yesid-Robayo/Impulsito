package io.github.yesidrobayo.shopledger.surface

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import io.github.yesidrobayo.shopledger.domain.model.surface.DeviceLockState

/** Lee del sistema si la pantalla está encendida y si el teléfono está bloqueado. */
object LockStateReader {
    /** Si un servicio del sistema no responde, la lectura cuenta como bloqueada. */
    fun read(context: Context): DeviceLockState {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return DeviceLockState(
            screenOn = power?.isInteractive ?: false,
            keyguardShowing = keyguard?.isKeyguardLocked ?: true,
            deviceLocked = keyguard?.isDeviceLocked ?: true,
        )
    }
}
