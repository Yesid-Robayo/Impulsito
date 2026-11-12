package io.github.yesidrobayo.shopledger.data.safety

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Sufijo del archivo temporal que precede a cada renombrado. */
internal const val TEMPORARY_SUFFIX = ".tmp"

/**
 * Escribe [text] en [target] pasando por un temporal y un renombrado, para que la muerte del
 * proceso a mitad de la escritura nunca deje el archivo a medias.
 *
 * No llama a fsync a propósito: estos archivos solo tienen que sobrevivir a la muerte del proceso,
 * y el kernel conserva lo ya escrito aunque el proceso muera. Un corte de energía puede perder el
 * último cambio, y eso solo cuesta un intento del conteo o un registro de fallo. A cambio, el
 * arranque no espera al disco.
 */
internal fun writeAtomically(target: File, text: String) {
    // Una ruta absoluta siempre tiene carpeta, salvo la raíz, que nunca es un destino.
    val directory = checkNotNull(target.absoluteFile.parentFile)
    directory.mkdirs()
    val temporary = File(directory, target.name + TEMPORARY_SUFFIX)
    temporary.writeText(text, Charsets.UTF_8)
    try {
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        // Solo pasa en sistemas de archivos sin renombrado atómico. Reemplazar sigue siendo mejor
        // que dejar el temporal.
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
