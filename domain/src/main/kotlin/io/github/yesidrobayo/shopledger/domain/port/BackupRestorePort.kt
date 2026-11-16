package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.UndoRestoreCopy

/**
 * Restaurar los datos desde una copia: de la lista del destino conectado, o desde un archivo que la
 * persona eligió con el selector. Toda copia es entrada no confiable: se revisa entera antes de
 * tocar nada, y los datos solo se reemplazan al reiniciar la app.
 */
interface BackupRestorePort {
    /**
     * Empieza con la copia de la lista cuya clave es [copyKey]. [code] es el código de recuperación
     * que escribió la persona, si la copia lo pidió: solo vive en memoria, y nunca se guarda tal cual.
     */
    suspend fun restoreCopy(copyKey: String, code: String? = null): RestoreProgress

    /**
     * Empieza con el archivo [uri] que se eligió con el selector, sin suma esperada. [code] es el
     * código escrito, si el archivo lo pidió.
     */
    suspend fun restoreFile(uri: String, code: String? = null): RestoreProgress

    /**
     * La copia previa a la última actualización de los datos, o nula si no hay ninguna. Solo lee
     * el disco.
     */
    suspend fun beforeUpdateCopy(): BeforeUpdateCopy?

    /**
     * Empieza a volver a la copia previa a la última actualización: se revisa entera, se migra con
     * las migraciones de esta versión y, al reiniciar, reemplaza los datos. Lo registrado después
     * se pierde, y la copia de seguridad lo guarda.
     */
    suspend fun restoreBeforeUpdate(): RestoreProgress

    /**
     * La copia local de seguridad que se ofrece para volver a como estaba antes de restaurar, o
     * nula si no hay ninguna de menos de 30 días. Con dos, la más reciente. Solo lee el disco.
     */
    suspend fun undoRestoreCopy(): UndoRestoreCopy?

    /**
     * Empieza a volver a [copy]: se revisa entera como cualquier copia y, al reiniciar, reemplaza
     * los datos. Lo registrado después se pierde, y la copia de seguridad de ahora lo guarda. Esa
     * copia nunca subió, así que al terminar los datos cuentan como pendientes de copia. Si
     * [copy] ya no está, o venció, no empieza.
     */
    suspend fun restoreUndo(copy: UndoRestoreCopy): RestoreProgress

    /** Sigue la restauración detenida en una pregunta con [answer]. */
    suspend fun answer(answer: RestoreAnswer): RestoreProgress

    /** Cancela la restauración que espera una respuesta. `false` si no había ninguna. */
    suspend fun cancel(): Boolean
}
