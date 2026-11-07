package io.github.yesidrobayo.shopledger.data.crypto

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Escribe y borra archivos pequeños de forma durable: un corte de energía deja el archivo de
 * antes o el nuevo, nunca uno a medias, y lo que se confirmó sobrevive al corte.
 *
 * Se escribe un `.tmp`, se sincroniza, se renombra y se sincroniza la carpeta: un renombrado no es
 * durable hasta que la carpeta que lo contiene llega al disco.
 */
internal object DurableFile {
    /** El temporal de [file], al lado. */
    fun temporaryOf(file: File): File = File(requireNotNull(file.parentFile), file.name + ".tmp")

    fun write(file: File, bytes: ByteArray) {
        val dir = requireNotNull(file.parentFile) { "El archivo necesita una carpeta" }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No se pudo crear la carpeta")
        val tmp = temporaryOf(file)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("No se pudo renombrar el archivo")
        } finally {
            // Si algo falló antes del renombrado, el temporal no queda: nunca hay dos versiones.
            if (tmp.exists()) tmp.delete()
        }
        syncDirectory(dir)
    }

    /** Borra [file] y su temporal. Devuelve `false` si alguno quedó. */
    fun delete(file: File): Boolean {
        val dir = file.parentFile ?: return true
        val removed = listOf(file, temporaryOf(file)).all { !it.exists() || it.delete() }
        if (dir.isDirectory) syncDirectory(dir)
        return removed
    }

    private fun syncDirectory(dir: File) {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            throw IOException("No se pudo sincronizar la carpeta", e)
        }
    }
}
