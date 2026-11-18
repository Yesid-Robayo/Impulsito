package io.github.yesidrobayo.shopledger.data.update

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import io.github.yesidrobayo.shopledger.domain.model.update.VersionManifest
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
 * Lo que este teléfono recuerda de la búsqueda de versión, en `update-check.json`, dentro de
 * [directory], que en la app es `noBackupFilesDir`: es de este teléfono, no del negocio, así que no
 * viaja en una copia, y `Borrar mis datos` lo borra con el resto de esa carpeta.
 *
 * Guarda cuándo se buscó y cuándo salió el último recordatorio, si la búsqueda diaria está
 * encendida, la última versión avisada con una notificación y la versión más nueva que dio la
 * última búsqueda que leyó un aviso firmado: su número y su nombre, nada más del aviso. Se escribe
 * con `.tmp`, sincronización y renombrado: un corte de energía deja lo de antes o lo nuevo. Un
 * archivo que no se entiende es como no haber buscado nunca, con la búsqueda diaria encendida: a lo
 * sumo adelanta un recordatorio o repite un aviso.
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

    /** Enciende o apaga la búsqueda diaria. Si no quedó en el disco, responde `false`. */
    suspend fun setAutomatic(enabled: Boolean): Boolean = update { it.copy(automatic = enabled) }

    /** Anota que la versión [versionCode] ya salió en una notificación. */
    suspend fun markNotified(versionCode: Long): Boolean =
        update { it.copy(lastNotifiedVersionCode = versionCode) }

    /** Guarda la versión más nueva conocida, o la borra si es nula. */
    suspend fun setKnown(known: KnownUpdate?): Boolean = update { it.copy(known = known) }

    private suspend fun update(change: (Marks) -> Marks): Boolean = mutex.withLock {
        withContext(ioContext) {
            val marks = change(load())
            val root = JSONObject()
            marks.lastCheckAt?.let { root.put(LAST_CHECK_AT, it.toEpochMilliseconds()) }
            marks.lastReminderAt?.let { root.put(LAST_REMINDER_AT, it.toEpochMilliseconds()) }
            root.put(AUTOMATIC, marks.automatic)
            marks.lastNotifiedVersionCode?.let { root.put(LAST_NOTIFIED_VERSION_CODE, it) }
            marks.known?.let {
                root.put(KNOWN_VERSION_CODE, it.versionCode)
                root.put(KNOWN_VERSION_NAME, it.versionName)
            }
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
        return Marks(
            lastCheckAt = instantOf(root, LAST_CHECK_AT),
            lastReminderAt = instantOf(root, LAST_REMINDER_AT),
            // Sin el campo, o con otra cosa, la búsqueda diaria sigue encendida, como al instalar.
            automatic = root.opt(AUTOMATIC) as? Boolean ?: true,
            lastNotifiedVersionCode = positive(root, LAST_NOTIFIED_VERSION_CODE),
            known = knownOf(root),
        )
    }

    /** La versión conocida, o nula si falta su número o su nombre no es un nombre de versión. */
    private fun knownOf(root: JSONObject): KnownUpdate? {
        val code = positive(root, KNOWN_VERSION_CODE) ?: return null
        val name = (root.opt(KNOWN_VERSION_NAME) as? String)?.trim()
        if (name.isNullOrEmpty() || name.length > VersionManifest.MAX_NAME_LENGTH) return null
        return KnownUpdate(code, name)
    }

    private fun positive(root: JSONObject, name: String): Long? =
        when (val value = root.opt(name)) {
            is Long -> value
            is Int -> value.toLong()
            else -> null
        }?.takeIf { it > 0 }

    private fun instantOf(root: JSONObject, name: String): Instant? =
        positive(root, name)?.let(Instant::fromEpochMilliseconds)

    /**
     * @property lastCheckAt cuándo la persona tocó `Buscar actualización` por última vez.
     * @property lastReminderAt cuándo salió el último recordatorio de revisar.
     * @property automatic si la búsqueda diaria está encendida. Nace encendida.
     * @property lastNotifiedVersionCode la última versión que salió en una notificación.
     * @property known la versión más nueva que dio la última búsqueda con un aviso firmado.
     */
    data class Marks(
        val lastCheckAt: Instant?,
        val lastReminderAt: Instant?,
        val automatic: Boolean = true,
        val lastNotifiedVersionCode: Long? = null,
        val known: KnownUpdate? = null,
    ) {
        companion object {
            val NONE = Marks(null, null)
        }
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "update-check.json"

        private const val LAST_CHECK_AT = "last_check_at"
        private const val LAST_REMINDER_AT = "last_reminder_at"
        private const val AUTOMATIC = "automatic"
        private const val LAST_NOTIFIED_VERSION_CODE = "last_notified_version_code"
        private const val KNOWN_VERSION_CODE = "known_version_code"
        private const val KNOWN_VERSION_NAME = "known_version_name"
        private const val MAX_BYTES = 4 * 1024L
    }
}
