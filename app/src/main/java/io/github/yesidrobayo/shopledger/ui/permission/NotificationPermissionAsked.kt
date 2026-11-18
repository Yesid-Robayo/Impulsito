package io.github.yesidrobayo.shopledger.ui.permission

import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Si la app ya ofreció sola el permiso de avisos en este teléfono, con el archivo vacío
 * `notification-permission-asked` en la carpeta que da [directory], que en la app es
 * `noBackupFilesDir`.
 *
 * **Por qué un archivo y no la base.** Es de este teléfono, no del negocio: no viaja en una copia,
 * y restaurar una copia en otro teléfono no le quita a ese teléfono su propia pregunta. Si no se
 * pudo escribir, la pregunta puede salir otra vez en la próxima apertura: a lo sumo, una vez más.
 * Todo toca el disco en [io].
 */
class NotificationPermissionAsked(
    private val directory: () -> File,
    private val io: CoroutineContext,
) {
    /** Si ya se ofreció. */
    suspend fun wasAsked(): Boolean = withContext(io) { File(directory(), FILE_NAME).exists() }

    /** Anota que se ofreció. `false` si no quedó en el disco; nada falla. */
    suspend fun markAsked(): Boolean = withContext(io) {
        try {
            val folder = directory()
            folder.mkdirs()
            val file = File(folder, FILE_NAME)
            file.exists() || file.createNewFile()
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        /** El nombre del archivo, en inglés y para siempre. */
        const val FILE_NAME = "notification-permission-asked"
    }
}
