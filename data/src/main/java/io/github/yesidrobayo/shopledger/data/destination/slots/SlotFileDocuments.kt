package io.github.yesidrobayo.shopledger.data.destination.slots

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileRejection

/**
 * Lo que la app hace con un documento que la persona acaba de crear en el selector del sistema para
 * una ranura. Así las reglas de la preparación se prueban con documentos inventados, y solo el
 * adaptador conoce el `ContentResolver`.
 *
 * Un documento se nombra por su URI en texto: es un dato, nunca una ruta, y nunca va a un log.
 * Ninguna operación escribe ni borra el documento.
 */
internal interface SlotFileDocuments {
    /**
     * Toma el permiso persistente de lectura y escritura de [uri]. Devuelve `false` si el proveedor
     * no lo concede: entonces la ranura no serviría con la app cerrada.
     */
    fun takePermission(uri: String): Boolean

    /** Suelta el permiso persistente de [uri], si lo había. */
    fun releasePermission(uri: String)

    /** Lo que el proveedor dice de [uri], sin abrirlo, o nulo si no responde. */
    fun inspect(uri: String): PickedDocument?
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

/**
 * Si [document], recién creado para una ranura, sirve: que se pueda escribir, que el proveedor diga
 * su tamaño y que esté vacío. Es nulo si sirve.
 *
 * Un archivo con datos no es nuevo: el selector deja elegir uno que ya existe, que puede ser la
 * copia de otro teléfono o una ranura vieja, y escribir ahí borraría lo que tiene. Sin tamaño, la
 * guarda que compara el tamaño antes de cada escritura no podría notar si otro lo cambió.
 */
internal fun rejectionOf(document: PickedDocument): SlotFileRejection? = when {
    !document.writable -> SlotFileRejection.NOT_WRITABLE
    document.sizeBytes == null -> SlotFileRejection.SIZE_UNKNOWN
    document.sizeBytes != 0L -> SlotFileRejection.NOT_EMPTY
    else -> null
}

/** Los documentos por el `ContentResolver` del sistema. */
internal class ResolverSlotFileDocuments(private val resolver: ContentResolver) :
    SlotFileDocuments {
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
            } catch (_: UnsupportedOperationException) {
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
