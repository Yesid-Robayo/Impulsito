package io.github.yesidrobayo.shopledger.data.backup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import io.github.yesidrobayo.shopledger.domain.model.backup.MetaValueForm
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * El identificador de esta instalación: un UUID aleatorio, en su texto canónico de 36 caracteres
 * en minúscula, guardado en el archivo `device_id` de [directory], que en la app es
 * `noBackupFilesDir`.
 *
 * Con él cada copia dice qué teléfono la hizo, y así la app nota que otro teléfono escribió en el
 * mismo destino antes de pisar nada. Identifica la instalación, no los datos: por eso no vive en la
 * base, no viaja en una copia y restaurar no lo cambia. Si la persona borra los datos de la app,
 * nace otro, igual que la base.
 *
 * - Sale de [UUID.randomUUID], que usa el generador criptográfico del sistema. Nunca de un dato del
 *   teléfono como su número de serie, su identificador de Android o el de publicidad, que lo
 *   identificarían fuera de la app.
 * - Se crea una sola vez, la primera vez que hace falta, y después siempre se lee el mismo.
 * - Se escribe en `device_id.tmp`, se sincroniza con el disco y se renombra, y después se
 *   sincroniza la carpeta, para que el nombre nuevo también sobreviva a un corte: un corte a mitad
 *   deja el archivo de antes o ninguno, nunca uno a medias.
 * - Si el archivo existe pero no trae un UUID canónico, nace otro. Con un identificador nuevo, las
 *   copias de antes cuentan como de otro teléfono: la app pregunta antes de escribir sobre ellas, y
 *   no deja de respaldar por un archivo dañado.
 * - Nunca se registra en un log.
 *
 * Una sola instancia por proceso: la caché y el cerrojo viven en ella.
 *
 * @param ioContext contexto de las lecturas y escrituras del archivo, fuera del hilo principal.
 */
internal class DeviceIdentityStore(
    private val directory: File,
    private val ioContext: CoroutineContext,
) {
    private val mutex = Mutex()

    @Volatile private var cached: String? = null

    /** El identificador de la instalación. Lo crea si todavía no existe. */
    suspend fun deviceId(): String {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(ioContext) { readOrCreate() }.also { cached = it }
        }
    }

    private fun readOrCreate(): String {
        val file = File(directory, FILE_NAME)
        if (file.isFile) {
            val stored = file.readText(Charsets.US_ASCII).trim()
            if (MetaValueForm.Uuid.accepts(stored)) return stored
        }
        val created = UUID.randomUUID().toString()
        directory.mkdirs()
        val temporary = File(directory, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(created.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        syncDirectory()
        return created
    }

    /** Sincroniza la carpeta con el disco: el renombrado vive en ella, no en el archivo. */
    private fun syncDirectory() {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    companion object {
        /** Nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "device_id"

        /** El de la app, en `noBackupFilesDir`, que el sistema nunca respalda ni transfiere. */
        fun inNoBackupDir(context: Context, ioContext: CoroutineContext): DeviceIdentityStore =
            DeviceIdentityStore(context.noBackupFilesDir, ioContext)
    }
}
