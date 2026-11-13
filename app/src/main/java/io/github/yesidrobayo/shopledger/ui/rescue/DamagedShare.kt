package io.github.yesidrobayo.shopledger.ui.rescue

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * La carpeta de lo que sale de la app por el selector del sistema: `cache/exports/`. Es la única
 * que el proveedor de archivos comparte, y solo para leer. Se vacía antes de cada exportación,
 * cuando la persona vuelve del selector y al abrir el rescate: nada de lo que sale queda guardado.
 */
internal object ExportFolder {
    /** El nombre de la carpeta dentro de la caché. Coincide con el camino del proveedor. */
    const val NAME = "exports"

    /** El paquete con la base dañada. El nombre no dice nada del negocio. */
    const val DAMAGED_FILE_NAME = "damaged-data.zip"

    /** El tipo del paquete. */
    const val ZIP_TYPE = "application/zip"

    /** El registro de fallos, como texto. */
    const val CRASH_LOG_FILE_NAME = "crash-log.txt"

    /** El tipo del registro de fallos. */
    const val TEXT_TYPE = "text/plain"

    /** El tipo de un documento para compartir, como el extracto de un cliente. */
    const val PDF_TYPE = "application/pdf"

    /** La carpeta. Pedirla no toca el disco. */
    fun of(context: Context): File = File(context.cacheDir, NAME)

    /** La autoridad del proveedor de archivos de la app. */
    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /** Vacía la carpeta. Lee y escribe el disco: va fuera del hilo principal. */
    fun clear(context: Context) {
        of(context).listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    private const val AUTHORITY_SUFFIX = ".fileprovider"
}

/**
 * Deja en `cache/exports/` el paquete con la base dañada, con [export], y devuelve su dirección para
 * compartir, o nula si no hay base dañada o no se pudo escribir. Si algo falla, la carpeta queda
 * vacía. Lee y escribe el disco: va fuera del hilo principal.
 */
internal suspend fun exportDamaged(context: Context, export: suspend (File) -> Boolean): Uri? =
    exportFile(context, ExportFolder.DAMAGED_FILE_NAME, export)

/**
 * Deja en `cache/exports/` el registro de fallos, [text], y devuelve su dirección para compartir, o
 * nula si no se pudo escribir. Si algo falla, la carpeta queda vacía. Lee y escribe el disco.
 */
internal suspend fun exportCrashLog(context: Context, text: String): Uri? =
    exportFile(context, ExportFolder.CRASH_LOG_FILE_NAME) { target ->
        target.writeText(text, Charsets.UTF_8)
        true
    }

/**
 * Vacía la carpeta, escribe [name] con [write] y da su dirección; si algo falla, no deja nada. Lee y
 * escribe el disco: va fuera del hilo principal.
 */
internal suspend fun exportFile(
    context: Context,
    name: String,
    write: suspend (File) -> Boolean,
): Uri? {
    var shared: Uri? = null
    try {
        ExportFolder.clear(context)
        val folder = ExportFolder.of(context)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Sin carpeta de exportación")
        val target = File(folder, name)
        if (write(target)) {
            shared = FileProvider.getUriForFile(context, ExportFolder.authority(context), target)
        }
        return shared
    } catch (_: IOException) {
        return null
    } catch (_: IllegalArgumentException) {
        // El proveedor no comparte ese camino: no se comparte nada.
        return null
    } finally {
        if (shared == null) ExportFolder.clear(context)
    }
}

/**
 * El selector del sistema para compartir [uri], con permiso solo de lectura. Lo lanza un toque de
 * la persona, después de la advertencia: nunca sale solo.
 */
internal fun damagedShareIntent(uri: Uri): Intent = shareIntent(uri, ExportFolder.ZIP_TYPE)

/** El selector del sistema para compartir el registro de fallos, ya mostrado en la pantalla. */
internal fun crashLogShareIntent(uri: Uri): Intent = shareIntent(uri, ExportFolder.TEXT_TYPE)

/** El selector del sistema para compartir un PDF de `cache/exports/`, con permiso solo de lectura. */
internal fun pdfShareIntent(uri: Uri): Intent = shareIntent(uri, ExportFolder.PDF_TYPE)

private fun shareIntent(uri: Uri, type: String): Intent {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // El permiso viaja con el contenido del recorte: así llega también a la app que se elija.
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, null)
}
