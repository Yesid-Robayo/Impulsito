package io.github.yesidrobayo.shopledger.data.destination.slots

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.yesidrobayo.shopledger.data.destination.SHA256_HEX
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * El estado de las ranuras en `slots.json`, dentro de [directory], que en la app es
 * `noBackupFilesDir`: el sistema nunca lo respalda ni lo transfiere a otro teléfono.
 *
 * Se escribe como el diario: `slots.tmp`, sincronizado con el disco, renombrado sobre `slots.json`,
 * y después la carpeta sincronizada, porque el renombrado no es durable hasta sincronizarla. Sin
 * eso, tras un corte de energía el archivo podría volver a afirmar una copia que la escritura ya
 * vació, y la app lo leería como un cambio hecho por otro teléfono.
 *
 * Se lee con reglas estrictas: un campo que falta, de otro tipo o fuera de su forma deja el estado
 * entero ilegible, y la app no escribe en ninguna ranura.
 */
internal class FileSlotStateStore(
    private val directory: File,
    private val ioContext: CoroutineContext,
) : SlotStateStore {
    override suspend fun load(): SlotStateLoad = withContext(ioContext) {
        val file = File(directory, FILE_NAME)
        if (!file.exists()) return@withContext SlotStateLoad.Missing
        try {
            SlotStateLoad.Loaded(decode(file.readText(Charsets.UTF_8)))
        } catch (_: IOException) {
            SlotStateLoad.Unreadable
        } catch (_: JSONException) {
            SlotStateLoad.Unreadable
        } catch (_: IllegalArgumentException) {
            SlotStateLoad.Unreadable
        }
    }

    override suspend fun save(state: SlotState) {
        withContext(ioContext) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Sin carpeta")
            val temporary = File(directory, TEMPORARY_NAME)
            FileOutputStream(temporary).use { output ->
                output.write(encode(state).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                File(directory, FILE_NAME).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory()
        }
    }

    override suspend fun delete(): Boolean = withContext(ioContext) {
        val removed =
            listOf(File(directory, FILE_NAME), File(directory, TEMPORARY_NAME))
                .all { !it.exists() || it.delete() }
        if (directory.isDirectory) syncDirectory()
        removed
    }

    private fun syncDirectory() {
        try {
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        } catch (error: ErrnoException) {
            throw IOException("La carpeta no se sincronizó", error)
        }
    }

    companion object {
        /** El nombre del archivo dentro de [directory]. */
        const val FILE_NAME = "slots.json"

        /** El nombre mientras se escribe. Nunca se lee. */
        const val TEMPORARY_NAME = "slots.tmp"

        private const val VERSION = 1

        /** El de la app, en `noBackupFilesDir`. */
        fun inNoBackupDir(context: Context, ioContext: CoroutineContext): FileSlotStateStore =
            FileSlotStateStore(context.noBackupFilesDir, ioContext)

        /** El texto de [state], igual al que se guarda. */
        fun encode(state: SlotState): String {
            val slots = JSONObject()
            for (slot in BackupSlot.entries) slots.put(slot.name, encodeEntry(state[slot]))
            return JSONObject()
                .put("version", VERSION)
                .put("first_handoff_at", state.firstHandoffAt ?: JSONObject.NULL)
                .put("write_mode", state.writeMode.mode)
                .put("session_required", state.sessionRequired)
                .put("slots", slots)
                .toString()
        }

        /**
         * El estado que dice [text].
         *
         * @throws IllegalArgumentException o [JSONException] si no es un estado válido.
         */
        fun decode(text: String): SlotState {
            val root = JSONObject(text)
            require(root.getInt("version") == VERSION) { "Versión desconocida" }
            val slotsObject = root.getJSONObject("slots")
            require(slotsObject.length() == BackupSlot.entries.size) { "Ranuras de más" }
            val slots = BackupSlot.entries.associateWith {
                decodeEntry(slotsObject.getJSONObject(it.name))
            }
            val mode = root.getString("write_mode")
            return SlotState(
                slots = slots,
                firstHandoffAt = root.longOrNull("first_handoff_at"),
                writeMode = requireNotNull(
                    WriteMode.entries.firstOrNull {
                        it.mode == mode
                    },
                ) { "Modo desconocido" },
                sessionRequired = root.strictBoolean("session_required"),
            )
        }

        private fun encodeEntry(entry: SlotEntry): JSONObject {
            val json = JSONObject()
                .put("uri", entry.uri)
                .put("provider_label", entry.providerLabel)
                .put("display_name", entry.displayName ?: JSONObject.NULL)
                .put("copy", entry.copy?.let(::encodeRecord) ?: JSONObject.NULL)
                .put("writing_generation", entry.writing?.generation ?: JSONObject.NULL)
                .put("writing_meta", entry.writing?.meta?.let(::encodeRecord) ?: JSONObject.NULL)
            return json
        }

        private fun decodeEntry(json: JSONObject): SlotEntry {
            val writingGeneration = json.longOrNull("writing_generation")
            val writingMeta = json.objectOrNull("writing_meta")?.let(::decodeRecord)
            require(writingGeneration != null || writingMeta == null) {
                "Datos de escritura sin marca"
            }
            return SlotEntry(
                uri = json.strictString("uri"),
                providerLabel = json.strictString("provider_label"),
                displayName = json.stringOrNull("display_name"),
                copy = json.objectOrNull("copy")?.let(::decodeRecord),
                writing = writingGeneration?.let { WritingMark(it, writingMeta) },
            )
        }

        private fun encodeRecord(record: SlotRecord): JSONObject = JSONObject()
            .put("generation", record.generation)
            .put("written_at", record.writtenAt)
            .put("size", record.sizeBytes)
            .put("sha256", record.sha256)
            .put("valid", record.facts.valid)
            .put("sl_records", record.facts.records)
            .put("sl_catalog_count", record.facts.catalogCount)
            .put("meta", Base64.getEncoder().encodeToString(record.metaBytes))

        private fun decodeRecord(json: JSONObject): SlotRecord {
            val sha256 = json.strictString("sha256")
            require(SHA256_HEX.matches(sha256)) { "Suma con otra forma" }
            return SlotRecord(
                generation = json.strictLong("generation"),
                writtenAt = json.strictLong("written_at"),
                sizeBytes = json.strictLong("size"),
                sha256 = sha256,
                facts = SlotCopyFacts(
                    valid = json.strictBoolean("valid"),
                    records = json.strictLong("sl_records"),
                    catalogCount = json.strictLong("sl_catalog_count"),
                ),
                metaBytes = Base64.getDecoder().decode(json.strictString("meta")),
            )
        }

        // org.json convierte tipos sin avisar, como "12" a 12 o "true" a true. Estas lecturas
        // exigen el tipo exacto, para que un archivo dañado se lea como ilegible y no como otra
        // copia.

        private fun JSONObject.strictString(name: String): String {
            val value = get(name)
            require(value is String) { "Se esperaba texto" }
            return value
        }

        private fun JSONObject.stringOrNull(name: String): String? =
            if (isNull(name)) nullIfPresent(name) else strictString(name)

        private fun JSONObject.strictLong(name: String): Long {
            val value = get(name)
            require(value is Int || value is Long) { "Se esperaba un entero" }
            return (value as Number).toLong()
        }

        private fun JSONObject.longOrNull(name: String): Long? =
            if (isNull(name)) nullIfPresent(name) else strictLong(name)

        private fun JSONObject.strictBoolean(name: String): Boolean {
            val value = get(name)
            require(value is Boolean) { "Se esperaba un valor lógico" }
            return value
        }

        private fun JSONObject.objectOrNull(name: String): JSONObject? {
            if (isNull(name)) return nullIfPresent(name)
            val value = get(name)
            require(value is JSONObject) { "Se esperaba un objeto" }
            return value
        }

        /** Un campo nulo debe estar escrito como nulo: que falte también es daño. */
        private fun <T> JSONObject.nullIfPresent(name: String): T? {
            require(has(name)) { "Falta un campo" }
            return null
        }
    }
}
