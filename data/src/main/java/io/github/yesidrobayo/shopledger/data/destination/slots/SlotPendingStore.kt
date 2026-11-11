package io.github.yesidrobayo.shopledger.data.destination.slots

import android.content.Context
import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Un archivo creado para una ranura mientras faltan los demás.
 *
 * @property uri el documento, en texto. Es un dato, nunca va a un log.
 * @property providerLabel el nombre de la nube, de la lista fija de proveedores.
 * @property displayName su nombre visible, texto no confiable.
 */
internal data class PendingSlotFile(
    val uri: String,
    val providerLabel: String,
    val displayName: String?,
) {
    init {
        require(uri.isNotEmpty()) { "Un archivo sin documento" }
    }

    // Ni el documento ni su nombre van a un registro.
    override fun toString(): String = "PendingSlotFile"
}

/**
 * Los archivos ya creados de una preparación a medias, en `slots-pending.json`, dentro de
 * [directory], que en la app es `noBackupFilesDir`.
 *
 * Existe porque `slots.json` describe siempre las tres ranuras y no puede guardar medio juego: así,
 * si la persona sale a mitad, al volver sigue con los que falten. Se escribe con la durabilidad de
 * `slots.json`: un temporal sincronizado, el renombrado y la carpeta sincronizada. Se borra al
 * confirmar las tres ranuras.
 *
 * No manda sobre nada: cada entrada se vuelve a comprobar antes de usarla, y un archivo que no se lee
 * cuenta como vacío. Lo peor que pasa es que la persona cree ese archivo otra vez.
 */
internal class SlotPendingStore(
    private val directory: File,
    private val ioContext: CoroutineContext,
) {
    private val file get() = File(directory, FILE_NAME)

    /** Si el archivo existe, aunque no se lea. */
    suspend fun exists(): Boolean = withContext(ioContext) { file.exists() }

    /** Los archivos guardados, por ranura. Vacío si no hay archivo o no se lee. */
    suspend fun load(): Map<BackupSlot, PendingSlotFile> = withContext(ioContext) {
        val current = file
        if (!current.exists()) return@withContext emptyMap()
        try {
            decode(current.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            emptyMap()
        } catch (_: JSONException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }

    /**
     * Guarda [files] enteros. Cuando vuelve, sobrevive a un corte de energía.
     *
     * @throws IOException si no se pudo escribir. Lo de antes sigue intacto.
     */
    suspend fun save(files: Map<BackupSlot, PendingSlotFile>) {
        withContext(ioContext) {
            DurableFile.write(file, encode(files).toByteArray(Charsets.UTF_8))
        }
    }

    /** Borra el archivo. Devuelve `false` si quedó. */
    suspend fun delete(): Boolean = withContext(ioContext) { DurableFile.delete(file) }

    companion object {
        /** El nombre del archivo dentro de [directory]. */
        const val FILE_NAME = "slots-pending.json"

        private const val VERSION = 1

        /** El de la app, en `noBackupFilesDir`. */
        fun inNoBackupDir(context: Context, ioContext: CoroutineContext): SlotPendingStore =
            SlotPendingStore(context.noBackupFilesDir, ioContext)

        fun encode(files: Map<BackupSlot, PendingSlotFile>): String {
            val slots = JSONObject()
            for ((slot, entry) in files.toSortedMap()) {
                slots.put(
                    slot.name,
                    JSONObject()
                        .put("uri", entry.uri)
                        .put("provider_label", entry.providerLabel)
                        .put("display_name", entry.displayName ?: JSONObject.NULL),
                )
            }
            return JSONObject().put("version", VERSION).put("slots", slots).toString()
        }

        /**
         * Lo que dice [text].
         *
         * @throws IllegalArgumentException o [JSONException] si no es un avance válido.
         */
        fun decode(text: String): Map<BackupSlot, PendingSlotFile> {
            val root = JSONObject(text)
            require(root.getInt("version") == VERSION) { "Versión desconocida" }
            val slots = root.getJSONObject("slots")
            val result = LinkedHashMap<BackupSlot, PendingSlotFile>()
            for (key in slots.keys()) {
                val slot =
                    requireNotNull(BackupSlot.entries.firstOrNull { it.name == key }) {
                        "Ranura desconocida"
                    }
                val entry = slots.getJSONObject(key)
                result[slot] =
                    PendingSlotFile(
                        uri = entry.getString("uri"),
                        providerLabel = entry.getString("provider_label"),
                        displayName =
                            if (entry.isNull("display_name")) {
                                null
                            } else {
                                entry.getString("display_name")
                            },
                    )
            }
            return result
        }
    }
}
