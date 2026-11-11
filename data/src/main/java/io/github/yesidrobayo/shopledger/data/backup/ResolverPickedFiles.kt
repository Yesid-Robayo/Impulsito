package io.github.yesidrobayo.shopledger.data.backup

import android.content.ContentResolver
import android.net.Uri
import io.github.yesidrobayo.shopledger.data.destination.slots.ResolverSlotFileDocuments
import java.io.IOException
import java.io.InputStream

/**
 * Los archivos elegidos con el selector, por el `ContentResolver` del sistema. Solo se leen: la app
 * no toma permisos persistentes sobre un archivo que restaura.
 */
internal class ResolverPickedFiles(private val resolver: ContentResolver) : PickedFiles {
    private val documents = ResolverSlotFileDocuments(resolver)

    override fun declaredSize(uri: String): Long? = documents.inspect(uri)?.sizeBytes

    override fun open(uri: String): InputStream = try {
        resolver.openInputStream(Uri.parse(uri))
    } catch (_: SecurityException) {
        null
    } ?: throw IOException("El proveedor no entregó el archivo")
}
