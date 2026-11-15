package io.github.yesidrobayo.shopledger.data.update

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Cuándo se buscó una versión nueva por última vez y cuándo salió el último recordatorio, en
 * `update-check.json`, dentro de [directory], que en la app es `noBackupFilesDir`: es de este
 * teléfono, no del negocio, así que no viaja en una copia, y `Borrar mis datos` lo borra con el
 * resto de esa carpeta.
 *
 * Solo guarda dos fechas, sin nada de lo que respondió el aviso. Se escribe con `.tmp`,
 * sincronización y renombrado: un corte de energía deja las fechas de antes o las nuevas. Un
 * archivo que no se entiende es como no haber buscado nunca: a lo sumo adelanta un recordatorio.
 *
 * @param directory da la carpeta. Pedirla toca el disco: se pide en [ioContext].
 */
internal class UpdateCheckMarks(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
) {
    private val mutex = Mutex()

    /** Las dos fechas, cada una nula si nunca pasó. Nunca lanza por el contenido. */
    suspend fun read(): Marks = withContext(ioContext) { load() }

    /** Anota una búsqueda en [at]. Si no quedó en el disco, responde `false` y nada falla. */
    suspend fun markCheck(at: Instant): Boolean = update { it.copy(lastCheckAt = at) }

    /** Anota un recordatorio en [at]. Si no quedó en el disco, responde `false` y nada falla. */
    suspend fun markReminder(at: Instant): Boolean = update { it.copy(lastReminderAt = at) }

    private suspend fun update(change: (Marks) -> Marks): Boolean = mutex.withLock {
        withContext(ioContext) {
            val marks = change(load())
            val root = JSONObject()
            marks.lastCheckAt?.let { root.put(LAST_CHECK_AT, it.toEpochMilliseconds()) }
            marks.lastReminderAt?.let { root.put(LAST_REMINDER_AT, it.toEpochMilliseconds()) }
            try {
                DurableFile.write(file(), root.toString().toByteArray(Charsets.UTF_8))
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private fun file() = File(directory(), FILE_NAME)

    private fun load(): Marks {
        val file = file()
        if (!file.isFile || file.length() > MAX_BYTES) return Marks.NONE
        val root =
            try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (_: JSONException) {
                return Marks.NONE
            } catch (_: IOException) {
                return Marks.NONE
            }
        return Marks(instantOf(root, LAST_CHECK_AT), instantOf(root, LAST_REMINDER_AT))
    }

    private fun instantOf(root: JSONObject, name: String): Instant? =
        when (val value = root.opt(name)) {
            is Long -> value
            is Int -> value.toLong()
            else -> null
        }?.takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds)

    /**
     * @property lastCheckAt cuándo la persona tocó `Buscar actualización` por última vez.
     * @property lastReminderAt cuándo salió el último recordatorio de revisar.
     */
    data class Marks(val lastCheckAt: Instant?, val lastReminderAt: Instant?) {
        companion object {
            val NONE = Marks(null, null)
        }
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "update-check.json"

        private const val LAST_CHECK_AT = "last_check_at"
        private const val LAST_REMINDER_AT = "last_reminder_at"
        private const val MAX_BYTES = 4 * 1024L
    }
}
