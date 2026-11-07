package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * El destino conectado, tal como lo ve la app sin abrir la base: qué destino es, con qué usuario y
 * en qué carpeta. Nunca lleva un secreto.
 *
 * @property account el usuario WebDAV. No es secreto, pero tampoco va a un registro.
 * @property destinationId la huella del destino.
 * @property folderUrl la dirección de la carpeta de copias, con `/` final.
 * @property connectedAtMillis cuándo se conectó, según el reloj del teléfono, en milisegundos UTC.
 */
internal data class DestinationLink(
    val kind: BackupDestinationKind,
    val account: String?,
    val destinationId: String,
    val folderUrl: String?,
    val connectedAtMillis: Long,
) {
    // Ni el usuario ni la carpeta van a un registro.
    override fun toString(): String = "DestinationLink($kind)"
}

/**
 * Guarda [DestinationLink] en `link.json`, dentro de [directory], que en la app es
 * `noBackupFilesDir`. Sirve para saber qué destino había cuando la base no abre, y para terminar
 * un cambio de destino que se cortó: se escribe de último, y mientras difiera de la base, el
 * arranque sabe que falta algo.
 *
 * Se escribe con `.tmp`, sincronización y renombrado. Un archivo que no se entiende se lee como
 * ausente: nunca se usa a medias.
 */
internal class DestinationLinkStore(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
) {
    private fun file() = File(directory(), FILE_NAME)

    /** Escribe [link]. Lanza [IOException] si no quedó en el disco. */
    suspend fun write(link: DestinationLink) = withContext(ioContext) {
        val json =
            JSONObject()
                .put(KIND, link.kind.name)
                .put(ACCOUNT, link.account ?: JSONObject.NULL)
                .put(DESTINATION_ID, link.destinationId)
                .put(FOLDER, link.folderUrl ?: JSONObject.NULL)
                .put(CONNECTED_AT, link.connectedAtMillis)
        DurableFile.write(file(), json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Lo guardado, o `null` si no hay nada o no se entiende. */
    suspend fun read(): DestinationLink? = withContext(ioContext) {
        val file = file()
        if (!file.isFile || file.length() > MAX_BYTES) return@withContext null
        try {
            val json = JSONObject(String(file.readBytes(), Charsets.UTF_8))
            val kind =
                BackupDestinationKind.entries.firstOrNull { it.name == json.optString(KIND) }
                    ?: return@withContext null
            DestinationLink(
                kind = kind,
                account = json.optStringOrNull(ACCOUNT),
                destinationId = json.getString(DESTINATION_ID),
                folderUrl = json.optStringOrNull(FOLDER),
                connectedAtMillis = json.getLong(CONNECTED_AT),
            )
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /** Borra el archivo, al desconectar. Devuelve `false` si quedó algo. */
    suspend fun delete(): Boolean = withContext(ioContext) { DurableFile.delete(file()) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    companion object {
        const val FILE_NAME = "link.json"
        private const val MAX_BYTES = 16L * 1024
        private const val KIND = "destination_kind"
        private const val ACCOUNT = "destination_account"
        private const val DESTINATION_ID = "destination_id"
        private const val FOLDER = "destination_folder"
        private const val CONNECTED_AT = "connected_at"
    }
}
