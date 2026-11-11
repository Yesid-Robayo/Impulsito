package io.github.yesidrobayo.shopledger.data.harness

import android.content.ContentResolver
import android.net.Uri
import io.github.yesidrobayo.shopledger.data.destination.slots.ResolverSlotFileDocuments
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotFileDocuments
import java.io.IOException
import java.io.InputStream

/**
 * Lo que el arnés hace con los documentos que la persona eligió en el selector del sistema: lo mismo
 * que la pantalla de preparar los archivos, y además leerlos para restaurar. Así las reglas de la
 * preparación se prueban con documentos inventados, y solo el adaptador conoce el `ContentResolver`.
 *
 * Un documento se nombra por su URI en texto: es un dato, nunca una ruta, y nunca va a un log.
 */
internal interface HarnessDocuments : SlotFileDocuments {
    /** Abre [uri] para leerlo una vez, de principio a fin. */
    fun open(uri: String): InputStream
}

/** Los documentos por el `ContentResolver` del sistema. */
internal class ResolverHarnessDocuments(private val resolver: ContentResolver) :
    HarnessDocuments,
    SlotFileDocuments by ResolverSlotFileDocuments(resolver) {
    override fun open(uri: String): InputStream = try {
        resolver.openInputStream(Uri.parse(uri))
    } catch (_: SecurityException) {
        null
    } ?: throw IOException("El proveedor no entregó el archivo")
}
