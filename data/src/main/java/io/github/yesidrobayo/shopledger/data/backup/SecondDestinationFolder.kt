package io.github.yesidrobayo.shopledger.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * La carpeta del segundo destino, por el marco de acceso a documentos del sistema. Solo conoce
 * archivos por su nombre dentro de esa carpeta: un nombre nunca se usa como ruta local.
 *
 * Una carpeta que no aparece, como una tarjeta fuera o una carpeta borrada, lanza
 * [FolderMissingException]; un permiso perdido, [SecurityException].
 */
internal interface SecondDestinationFolder {
    /** Los archivos de la carpeta, por nombre, con su tamaño. */
    fun list(): Map<String, Long>

    /** Crea el archivo [name], vacío, y lo devuelve; falla si no se pudo crear con ese nombre. */
    fun create(name: String)

    /** Escribe todo [input] en [name], desde el principio, y lo deja en el disco. */
    fun write(name: String, input: InputStream)

    fun read(name: String): InputStream

    /** Cambia el nombre de [from] a [to]. Devuelve `false` si el sistema no lo hizo. */
    fun rename(from: String, to: String): Boolean

    /** Borra [name]. Devuelve `false` si quedó. */
    fun delete(name: String): Boolean
}

/** La carpeta no está: la tarjeta o la memoria puede estar fuera, o se borró. */
internal class FolderMissingException : IOException("La carpeta no está")

/**
 * La carpeta [folderUri], un documento dentro del árbol [treeUri] que eligió la persona con su
 * permiso persistente.
 */
internal class DocumentTreeFolder(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val folderUri: Uri,
) : SecondDestinationFolder {
    private val folderId: String = DocumentsContract.getDocumentId(folderUri)

    override fun list(): Map<String, Long> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val cursor =
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            ) ?: throw FolderMissingException()
        return cursor.use {
            // Una carpeta borrada o una tarjeta fuera da una consulta vacía, no un error: se
            // distingue preguntando por la carpeta misma.
            if (it.count == 0 && !folderExists()) throw FolderMissingException()
            buildMap {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    put(name, if (it.isNull(1)) 0L else it.getLong(1))
                }
            }
        }
    }

    override fun create(name: String) {
        val created =
            DocumentsContract.createDocument(resolver, folderUri, MIME_TYPE, name)
                ?: throw IOException("No se pudo crear el archivo")
        // Algunos proveedores cambian el nombre si ya existe: entonces no es el que se pidió.
        if (displayName(created) != name) {
            DocumentsContract.deleteDocument(resolver, created)
            throw IOException("El archivo quedó con otro nombre")
        }
    }

    override fun write(name: String, input: InputStream) {
        val descriptor =
            resolver.openFileDescriptor(uriOf(name), "wt")
                ?: throw FileNotFoundException("El archivo no se pudo abrir")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                input.copyTo(output, BUFFER_BYTES)
                output.flush()
                output.fd.sync()
            }
        }
    }

    override fun read(name: String): InputStream = resolver.openInputStream(uriOf(name))
        ?: throw FileNotFoundException("El archivo no se pudo leer")

    override fun rename(from: String, to: String): Boolean {
        val renamed = DocumentsContract.renameDocument(resolver, uriOf(from), to) ?: return false
        return displayName(renamed) == to
    }

    override fun delete(name: String): Boolean =
        DocumentsContract.deleteDocument(resolver, uriOf(name))

    private fun uriOf(name: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, "$folderId/$name")

    private fun folderExists(): Boolean = displayName(folderUri) != null

    private fun displayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val BUFFER_BYTES = 64 * 1024
    }
}
