package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationAcknowledgement
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Instant

/** Dónde se guarda cuándo se tocó `Entendido` en el aviso sin destino de Inicio. */
interface NoDestinationNoticeStore {
    /**
     * La aceptación guardada, o `null` si no hay o no se entiende: entonces el aviso va entero.
     *
     * @throws IOException si el archivo existe pero no se puede leer.
     */
    fun read(): NoDestinationAcknowledgement?

    /** @throws IOException si no se pudo escribir. */
    fun write(acknowledgement: NoDestinationAcknowledgement)

    /** Borra la aceptación, al elegir dónde guardar. No falla si no había. */
    fun clear()
}

/**
 * La aceptación del aviso sin destino, en el archivo `no-destination-notice` de la carpeta que da
 * [directory], que en la app es `noBackupFilesDir`: una línea con la versión, el instante en
 * milisegundos y la venta más reciente de ese momento, como `1 1794582000000 120`.
 *
 * **Por qué un archivo y no la base.** Es un ajuste de este teléfono, no un dato del negocio: no
 * viaja en una copia, restaurar no lo cambia y Android no respalda esta carpeta. Si se pierde, el
 * aviso vuelve entero, que es el lado seguro.
 *
 * - **Escribir** va a `no-destination-notice.tmp`, que se sincroniza con el disco y se renombra
 *   encima: un corte de energía deja la aceptación de antes o la nueva, nunca un archivo a medias.
 *   No se sincroniza la carpeta: perder el renombrado solo vuelve a mostrar el aviso entero.
 * - **Leer** toma a lo sumo [MAX_BYTES] bytes, y todo lo que no sea la línea esperada vale como
 *   ninguna aceptación.
 *
 * Todo toca el disco en el hilo que llama: quien llama lo saca del hilo principal.
 *
 * @param directory da la carpeta. Se pide en cada uso, porque pedir `noBackupFilesDir` también
 *   toca el disco.
 */
class NoDestinationNoticeFile(private val directory: () -> File) : NoDestinationNoticeStore {
    override fun read(): NoDestinationAcknowledgement? {
        val target = File(directory(), FILE_NAME)
        if (!target.isFile) return null
        val bytes =
            target.inputStream().use { input ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > MAX_BYTES) null else buffer.copyOf(size)
            } ?: return null
        return parse(String(bytes, Charsets.UTF_8))
    }

    override fun write(acknowledgement: NoDestinationAcknowledgement) {
        val folder = directory()
        folder.mkdirs()
        val temporary = File(folder, TEMPORARY_NAME)
        val line =
            "$VERSION ${acknowledgement.acknowledgedAt.toEpochMilliseconds()} " +
                "${acknowledgement.lastSaleId}\n"
        try {
            FileOutputStream(temporary).use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                File(folder, FILE_NAME).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            // Tras el renombrado ya no existe. Si algo falló antes, no debe quedar a medias.
            temporary.delete()
        }
    }

    override fun clear() {
        val target = File(directory(), FILE_NAME)
        if (target.exists() && !target.delete()) {
            throw IOException("No se pudo borrar la aceptación del aviso sin destino")
        }
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "no-destination-notice"

        /** El temporal de la escritura, que solo existe mientras dura. */
        const val TEMPORARY_NAME = "$FILE_NAME.tmp"

        /** La versión de la línea: otra versión no se entiende y el aviso va entero. */
        const val VERSION = "1"

        /** La versión, dos números y los espacios caben con holgura. */
        const val MAX_BYTES = 64

        /**
         * La línea guardada, o `null` si no es exactamente la versión y dos enteros no negativos.
         */
        fun parse(text: String): NoDestinationAcknowledgement? {
            val parts = text.trimEnd('\n').split(' ')
            if (parts.size != 3 || parts[0] != VERSION) return null
            val millis = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val sale = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return NoDestinationAcknowledgement(Instant.fromEpochMilliseconds(millis), sale)
        }
    }
}
