package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress

/**
 * Restaurar los datos desde una copia: de la lista del destino conectado, o desde un archivo que la
 * persona eligió con el selector. Toda copia es entrada no confiable: se revisa entera antes de
 * tocar nada, y los datos solo se reemplazan al reiniciar la app.
 */
interface BackupRestorePort {
    /** Empieza con la copia de la lista cuya clave es [copyKey]. */
    suspend fun restoreCopy(copyKey: String): RestoreProgress

    /** Empieza con el archivo [uri] que se eligió con el selector, sin suma esperada. */
    suspend fun restoreFile(uri: String): RestoreProgress

    /** Sigue la restauración detenida en una pregunta con [answer]. */
    suspend fun answer(answer: RestoreAnswer): RestoreProgress

    /** Cancela la restauración que espera una respuesta. `false` si no había ninguna. */
    suspend fun cancel(): Boolean
}
