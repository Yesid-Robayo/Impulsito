package io.github.yesidrobayo.shopledger.data.safety

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountKind
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountMarks
import io.github.yesidrobayo.shopledger.domain.port.CloudAccountChecklistPort
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Lo que la persona marcó de la cuenta de la nube, en `account-checklist.json`, dentro de
 * [directory], que en la app es `noBackupFilesDir`: es de la instalación, no del negocio, así que
 * no viaja en una copia ni el sistema la pasa a otro teléfono.
 *
 * Va en su propio archivo y no en `consents.json`: un `consents.json` sin las versiones aceptadas
 * se lee como una aceptación que hay que pedir otra vez, y marcar la lista antes de aceptar la
 * política haría aparecer el aviso de cambios a quien nunca aceptó nada.
 *
 * Guarda solo el tipo de destino y los puntos marcados, sin cuenta, dirección ni fecha. Se escribe
 * con `.tmp`, sincronización y renombrado: un corte de energía deja la lista de antes o la nueva.
 *
 * @param directory da la carpeta. Pedirla toca el disco: se pide en [ioContext].
 */
internal class CloudAccountChecklistFile(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
) : CloudAccountChecklistPort {
    private fun file() = File(directory(), FILE_NAME)

    /** Lo marcado. Nunca lanza por el contenido: lo que no se entiende es como no marcar nada. */
    override suspend fun load(): CloudAccountMarks? = withContext(ioContext) {
        val file = file()
        if (!file.isFile || file.length() > MAX_BYTES) return@withContext null
        val root =
            try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (_: JSONException) {
                return@withContext null
            } catch (_: IOException) {
                return@withContext null
            }
        val kind =
            CloudAccountKind.entries.firstOrNull { it.name == root.optString(KIND) }
                ?: return@withContext null
        val list = root.optJSONArray(CHECKED) ?: return@withContext null
        // Un punto que esta versión no conoce se ignora: nunca se marca otro en su lugar.
        val checked =
            (0 until list.length())
                .mapNotNull { index ->
                    CloudAccountCheck.entries.firstOrNull { it.name == list.optString(index) }
                }
                .toSet()
        CloudAccountMarks(kind, checked)
    }

    /** Guarda [marks] entero. Si no quedó en el disco, el archivo sigue como estaba. */
    override suspend fun save(marks: CloudAccountMarks): Boolean = withContext(ioContext) {
        val root = JSONObject()
        root.put(KIND, marks.kind.name)
        root.put(CHECKED, JSONArray(marks.checked.map { it.name }.sorted()))
        try {
            DurableFile.write(file(), root.toString().toByteArray(Charsets.UTF_8))
            true
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "account-checklist.json"

        /** El tipo de destino con que se marcó. */
        const val KIND = "kind"

        /** Los puntos marcados, por su nombre. */
        const val CHECKED = "checked"

        /** Tres puntos caben de sobra: un archivo más grande no es de la app. */
        private const val MAX_BYTES = 4L * 1024
    }
}
