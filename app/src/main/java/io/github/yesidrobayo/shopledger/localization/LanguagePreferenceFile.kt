package io.github.yesidrobayo.shopledger.localization

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * El idioma elegido, en el archivo `ui-language` de la carpeta que da [directory], que en la app es
 * `noBackupFilesDir`: una línea con la etiqueta, como `es`, o `system`.
 *
 * **Por qué un archivo y no la base.** El arranque aplica el idioma antes de abrir la base, la
 * pantalla de rescate funciona sin ella, y restaurar una copia reemplaza la base pero nunca toca
 * esta carpeta: el idioma de este teléfono no viaja en una copia ni cambia al restaurar. Android
 * tampoco respalda ni transfiere esta carpeta, así que un teléfono nuevo empieza sin archivo, es
 * decir, siguiendo al teléfono.
 *
 * - **Sin archivo** vale [UiLanguage.System].
 * - **Escribir** va a `ui-language.tmp`, que se sincroniza con el disco y se renombra encima, y
 *   después se sincroniza la carpeta, donde vive el nombre nuevo: un corte de energía a mitad deja
 *   el idioma de antes o el nuevo, nunca un archivo a medias.
 * - **Leer** toma a lo sumo [MAX_BYTES] bytes: un archivo más largo no es una etiqueta.
 *
 * Todo toca el disco en el hilo que llama. Quien llama decide si lo saca del hilo principal o, al
 * arrancar, lo cubre con un permiso de StrictMode solo para esta lectura.
 *
 * @param directory da la carpeta del archivo. Se pide en cada lectura y escritura, y no al crear
 *   esta clase, porque pedir `noBackupFilesDir` también toca el disco: Android crea la carpeta si
 *   falta. Así crearla no toca el disco.
 * @param syncDirectory sincroniza la carpeta con el disco tras el renombrado. Las pruebas en el PC
 *   lo reemplazan, porque la llamada del sistema solo existe en Android.
 */
class LanguagePreferenceFile(
    private val directory: () -> File,
    private val syncDirectory: (File) -> Unit = ::fsyncDirectory,
) {
    /** El archivo del idioma. Toca el disco, como [directory]. */
    val file: File get() = File(directory(), FILE_NAME)

    /**
     * Lee el idioma guardado.
     *
     * @return [LanguageRead.Missing] si no hay archivo, [LanguageRead.Invalid] si no trae una
     *   etiqueta ni `system`, o [LanguageRead.Found].
     * @throws IOException si el archivo existe pero no se puede leer.
     */
    fun read(): LanguageRead {
        val target = file
        if (!target.isFile) return LanguageRead.Missing
        val bytes =
            target.inputStream().use { input ->
                // Un byte de más basta para saber que el archivo no es una etiqueta.
                val buffer = ByteArray(MAX_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > MAX_BYTES) null else buffer.copyOf(size)
            }
        val language = bytes?.let { UiLanguage.parse(String(it, Charsets.UTF_8)) }
        return if (language == null) LanguageRead.Invalid else LanguageRead.Found(language)
    }

    /**
     * Guarda [language] con archivo temporal, sincronización y renombrado.
     *
     * @throws IOException si no se pudo escribir. Si falló antes del renombrado, el idioma anterior
     *   queda intacto y el temporal no queda en la carpeta. Si falló al sincronizar la carpeta, el
     *   archivo ya dice el idioma nuevo, pero un corte de energía todavía podría devolver el
     *   anterior.
     */
    fun write(language: UiLanguage) {
        val folder = directory()
        folder.mkdirs()
        val target = File(folder, FILE_NAME)
        val temporary = File(folder, TEMPORARY_NAME)
        try {
            FileOutputStream(temporary).use { output ->
                output.write((language.fileValue + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            // Tras el renombrado ya no existe. Si algo falló antes, no debe quedar a medias.
            temporary.delete()
        }
        syncDirectory(folder)
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "ui-language"

        /** El temporal de la escritura, que solo existe mientras dura. */
        const val TEMPORARY_NAME = "$FILE_NAME.tmp"

        /** Una etiqueta y su salto de línea caben con holgura. */
        const val MAX_BYTES = 64
    }
}

/** Lo que trae el archivo de idioma. */
sealed interface LanguageRead {
    /** No hay archivo: la app nunca guardó un idioma en este teléfono. */
    data object Missing : LanguageRead

    /** El archivo existe, pero no trae una etiqueta ni `system`. */
    data object Invalid : LanguageRead

    /** El idioma guardado. */
    data class Found(val language: UiLanguage) : LanguageRead
}

/** El idioma que vale para lo que leyó el archivo: sin archivo o con uno dañado, el del teléfono. */
val LanguageRead.language: UiLanguage
    get() = when (this) {
        LanguageRead.Missing, LanguageRead.Invalid -> UiLanguage.System
        is LanguageRead.Found -> language
    }

// El renombrado vive en la carpeta, no en el archivo: sin esto, un corte podría deshacerlo.
private fun fsyncDirectory(directory: File) {
    try {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    } catch (failure: ErrnoException) {
        throw IOException("No se pudo sincronizar la carpeta del idioma", failure)
    }
}
