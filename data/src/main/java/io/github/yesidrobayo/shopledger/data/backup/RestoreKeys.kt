package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCustody
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyRead
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeySave
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryState

/**
 * Lo que la restauración necesita del código de recuperación de este teléfono, para abrir una copia
 * cifrada y dejar su código guardado al terminar.
 *
 * Nada de esto cambia el sobre vigente ni el estado del cifrado: el código de la copia se sella
 * aparte, en `rk.pending`, y solo pasa a vigente si el reemplazo de la base queda verificado.
 */
internal interface RestoreKeys {
    /**
     * La clave del código guardado en este teléfono, si su sobre abre. Sigue sirviendo con el
     * cifrado apagado: las copias cifradas que quedan se restauran sin pedir el código.
     */
    suspend fun local(): BackupKey?

    /**
     * Si la persona apagó el cifrado en este teléfono. Entonces restaurar una copia cifrada no
     * cambia el código guardado: así nada lo vuelve a encender solo.
     */
    suspend fun turnedOffHere(): Boolean

    /** Sella [code] en `rk.pending`, al lado del sobre vigente. Devuelve si quedó durable. */
    suspend fun sealPending(code: RecoveryCode): Boolean

    /** Si hay un código guardado en este teléfono, abra o no. Solo mira si el sobre existe. */
    suspend fun savedHere(): Boolean

    companion object {
        /** Sin código en ninguna parte: una copia cifrada pide el suyo, y no se sella nada. */
        val None: RestoreKeys =
            object : RestoreKeys {
                override suspend fun local(): BackupKey? = null

                override suspend fun turnedOffHere(): Boolean = false

                override suspend fun sealPending(code: RecoveryCode): Boolean = false

                override suspend fun savedHere(): Boolean = false
            }
    }
}

/** [RestoreKeys] sobre la custodia del código: `rk.sealed`, `rk.pending` y `state.json`. */
internal class CustodyRestoreKeys(private val custody: RecoveryCustody) : RestoreKeys {
    override suspend fun local(): BackupKey? = when (val read = custody.keys.open()) {
        is RecoveryKeyRead.Available -> read.key

        // Sin sobre, o con uno que no abre ahora, se pide el código: escribirlo nunca borra nada.
        RecoveryKeyRead.Absent,
        is RecoveryKeyRead.Unavailable,
        is RecoveryKeyRead.RetryLater,
        -> null
    }

    override suspend fun turnedOffHere(): Boolean = custody.state.read().turnedOffHere()

    override suspend fun sealPending(code: RecoveryCode): Boolean =
        custody.keys.sealPending(code) is RecoveryKeySave.Sealed

    override suspend fun savedHere(): Boolean = custody.keys.exists()
}

/**
 * Si la persona apagó el cifrado en esta instalación. Un estado que nunca se activó, como en un
 * teléfono nuevo, no cuenta: ahí restaurar una copia cifrada deja el cifrado activo.
 */
internal fun RecoveryState.turnedOffHere(): Boolean = !enabled && offConfirmedAtUtcMillis != null
