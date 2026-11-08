package io.github.yesidrobayo.shopledger.data.harness

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream

/**
 * Lo que el arnés hace con los documentos que la persona eligió en el selector del sistema. Así las
 * reglas de la preparación se prueban con documentos inventados, y solo el adaptador conoce el
 * `ContentResolver`.
 *
 * Un documento se nombra por su URI en texto: es un dato, nunca una ruta, y nunca va a un log.
 */
internal interface HarnessDocuments {
    /**
     * Toma el permiso persistente de lectura y escritura de [uri]. Devuelve `false` si el proveedor
     * no lo concede: entonces la ranura no sirve con la app cerrada.
     */
    fun takePermission(uri: String): Boolean

    /** Suelta el permiso persistente de [uri]. Nunca escribe ni borra el documento. */
    fun releasePermission(uri: String)

    /** Lo que el proveedor dice de [uri], sin abrirlo, o nulo si no responde. */
    fun inspect(uri: String): PickedDocument?

    /** Abre [uri] para leerlo una vez, de principio a fin. */
    fun open(uri: String): InputStream
}

/**
 * Lo que el proveedor informa de un documento elegido.
 *
 * @property authority la autoridad de su URI: dice qué app lo guarda.
 * @property writable si el proveedor deja escribirlo.
 * @property sizeBytes su tamaño, o nulo si el proveedor no lo dice.
 * @property displayName su nombre visible, texto no confiable.
 */
internal data class PickedDocument(
    val authority: String?,
    val writable: Boolean,
    val sizeBytes: Long?,
    val displayName: String?,
) {
    // El nombre lo eligió la persona: no va a un registro.
    override fun toString(): String = "PickedDocument(writable=$writable)"
}

/** Los documentos por el `ContentResolver` del sistema. */
internal class ResolverHarnessDocuments(private val resolver: ContentResolver) : HarnessDocuments {
    override fun takePermission(uri: String): Boolean = try {
        resolver.takePersistableUriPermission(Uri.parse(uri), READ_WRITE)
        true
    } catch (_: SecurityException) {
        false
    }

    override fun releasePermission(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), READ_WRITE)
        } catch (_: SecurityException) {
            // No había permiso que soltar.
        }
    }

    override fun inspect(uri: String): PickedDocument? {
        val parsed = Uri.parse(uri)
        val cursor =
            try {
                resolver.query(parsed, COLUMNS, null, null, null)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val flags = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val size = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val name = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val flagBits = if (flags < 0 || it.isNull(flags)) 0 else it.getInt(flags)
            PickedDocument(
                authority = parsed.authority,
                writable = flagBits and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
                sizeBytes = if (size < 0 || it.isNull(size)) null else it.getLong(size),
                displayName = if (name < 0 || it.isNull(name)) null else it.getString(name),
            )
        }
    }

    override fun open(uri: String): InputStream = try {
        resolver.openInputStream(Uri.parse(uri))
    } catch (_: SecurityException) {
        null
    } ?: throw IOException("El proveedor no entregó el archivo")

    private companion object {
        const val READ_WRITE =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val COLUMNS =
            arrayOf(
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
    }
}
