package io.github.yesidrobayo.shopledger.data.destination.slots

import android.app.AuthenticationRequiredException
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * El acceso a los documentos de las ranuras por el selector de archivos del sistema.
 *
 * Toda excepción del proveedor se clasifica aquí y nunca sale con su mensaje, que puede traer la
 * cuenta o el nombre del archivo. `AuthenticationRequiredException` se separa antes que
 * `SecurityException`, de la que hereda: la salida es iniciar sesión en la otra app, no volver a
 * elegir los documentos.
 *
 * @param isPersistedGrant si la app conserva el permiso persistente de lectura y escritura de un
 *   documento. Por defecto lo mira en los permisos que el sistema guarda para la app.
 */
internal class ContentResolverDocumentAccess(
    private val resolver: ContentResolver,
    private val isPersistedGrant: (String) -> Boolean = { uri ->
        resolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission && it.isWritePermission
        }
    },
) : DocumentAccess {
    override suspend fun query(uri: String): DocumentResult<DocumentInfo> = classify {
        val cursor = resolver.query(Uri.parse(uri), COLUMNS, null, null, null)
            ?: return@classify DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        cursor.use {
            if (!it.moveToFirst()) {
                return@classify DocumentResult.Error(
                    DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE,
                )
            }
            val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
            val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val size = if (sizeColumn < 0 || it.isNull(sizeColumn)) null else it.getLong(sizeColumn)
            val name = if (nameColumn < 0 ||
                it.isNull(nameColumn)
            ) {
                null
            } else {
                it.getString(nameColumn)
            }
            DocumentResult.Ok(DocumentInfo(size, name))
        }
    }

    override suspend fun isPersisted(uri: String): Boolean = try {
        isPersistedGrant(uri)
    } catch (_: RuntimeException) {
        false
    }

    override suspend fun openForWrite(
        uri: String,
        mode: WriteMode,
    ): DocumentResult<WritableDocument> = classify {
        val descriptor = resolver.openFileDescriptor(Uri.parse(uri), mode.mode)
            ?: return@classify DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        DocumentResult.Ok(DescriptorDocument(descriptor))
    }

    override suspend fun openForRead(uri: String): DocumentResult<InputStream> = classify {
        val input = resolver.openInputStream(Uri.parse(uri))
            ?: return@classify DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        DocumentResult.Ok(input)
    }

    /** Un documento abierto por su descriptor. Cerrar el flujo cierra el descriptor. */
    private class DescriptorDocument(descriptor: ParcelFileDescriptor) : WritableDocument {
        private val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)

        override val stream: OutputStream get() = output

        override fun sync(): Boolean = try {
            output.fd.sync()
            true
        } catch (_: SyncFailedException) {
            false
        }

        override fun close() {
            output.close()
        }
    }

    private companion object {
        val COLUMNS = arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)

        inline fun <T> classify(block: () -> DocumentResult<T>): DocumentResult<T> = try {
            block()
        } catch (_: AuthenticationRequiredException) {
            DocumentResult.Error(DocumentError.AUTHENTICATION_REQUIRED)
        } catch (_: SecurityException) {
            DocumentResult.Error(DocumentError.SECURITY)
        } catch (_: FileNotFoundException) {
            DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        } catch (_: UnsupportedOperationException) {
            DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        } catch (_: IllegalArgumentException) {
            // Un documento que ya no existe hace fallar la consulta así en algunos proveedores.
            DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        } catch (_: IOException) {
            DocumentResult.Error(DocumentError.IO)
        } catch (_: RuntimeException) {
            DocumentResult.Error(DocumentError.REJECTED)
        }
    }
}
