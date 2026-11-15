package io.github.yesidrobayo.shopledger.notification

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.LimitNoticeMark
import io.github.yesidrobayo.shopledger.domain.port.LimitNoticeStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * El último aviso del límite de ingresos que salió, en el archivo `revenue-limit-notice` de la
 * carpeta que da [directory], que en la app es `noBackupFilesDir`: una línea con la versión, el
 * primer día de la ventana, el monto en unidad menor, los meses y el nivel, como
 * `1 2025-12-01 10000000 12 FIRST_NOTICE`.
 *
 * **Por qué un archivo y no la base.** Es de este teléfono, no un dato del negocio: no viaja en
 * una copia y Android no respalda esta carpeta. Si se pierde o no se entiende, el aviso puede
 * salir otra vez, que es el lado seguro.
 *
 * Escribir va a un temporal que se sincroniza y se renombra encima: un corte deja la línea de
 * antes o la nueva. Leer toma a lo sumo [MAX_BYTES] bytes. Todo toca el disco en [io].
 */
class LimitNoticeFile(private val directory: () -> File, private val io: CoroutineContext) :
    LimitNoticeStore {
    override suspend fun read(): LimitNoticeMark? = withContext(io) {
        val target = File(directory(), FILE_NAME)
        if (!target.isFile) {
            null
        } else {
            try {
                readBounded(target)?.let { parse(String(it, Charsets.UTF_8)) }
            } catch (_: IOException) {
                // Un archivo que no se lee vale como ningún aviso: a lo sumo, sale otra vez.
                null
            }
        }
    }

    override suspend fun write(mark: LimitNoticeMark) {
        withContext(io) {
            val folder = directory()
            folder.mkdirs()
            val temporary = File(folder, TEMPORARY_NAME)
            val line =
                "$VERSION ${mark.windowStart} ${mark.amount.minor} ${mark.months} " +
                    "${mark.level.name}\n"
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
    }

    /** Los bytes de [target], o `null` si pasa de [MAX_BYTES]: entonces no es una línea propia. */
    private fun readBounded(target: File): ByteArray? = target.inputStream().use { input ->
        val buffer = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        if (size > MAX_BYTES) null else buffer.copyOf(size)
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "revenue-limit-notice"

        /** El temporal de la escritura, que solo existe mientras dura. */
        const val TEMPORARY_NAME = "$FILE_NAME.tmp"

        /** La versión de la línea: otra versión no se entiende. */
        const val VERSION = "1"

        /** La versión, la fecha, dos números, el nivel y los espacios caben con holgura. */
        const val MAX_BYTES = 96

        private const val FIELDS = 5

        /** La línea guardada, o `null` si no es exactamente lo que se escribe. */
        fun parse(text: String): LimitNoticeMark? {
            val parts = text.trimEnd('\n').split(' ')
            if (parts.size != FIELDS || parts[0] != VERSION) return null
            val start = BusinessDate.parse(parts[1]) ?: return null
            val amount = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val months = parts[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val level = LimitLevel.entries.firstOrNull { it.name == parts[4] } ?: return null
            return LimitNoticeMark(start, Money(amount), months, level)
        }
    }
}
