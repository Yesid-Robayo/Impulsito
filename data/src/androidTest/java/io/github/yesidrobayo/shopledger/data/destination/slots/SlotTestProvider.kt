package io.github.yesidrobayo.shopledger.data.destination.slots

import android.app.AuthenticationRequiredException
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Un proveedor de documentos local, solo del APK de pruebas: guarda cada documento en la caché y
 * puede fallar como lo haría la nube del teléfono. No se exporta: solo lo abre la prueba, en su
 * mismo proceso. Ninguna prueba toca una nube real.
 */
internal class SlotTestProvider : ContentProvider() {
    /** Cómo se comporta un documento. */
    class Behavior {
        var present = true
        var sizeUnknown = false
        var queryFailure: Failure? = null
        val openFailures = mutableMapOf<String, Failure>()

        /** Entrega una tubería en vez del archivo, donde llevar al disco no aplica. */
        var pipe = false
    }

    enum class Failure { NOT_FOUND, UNSUPPORTED, SECURITY, AUTHENTICATION, OTHER }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val id = idOf(uri)
        val behavior = behaviors[id] ?: return null
        behavior.queryFailure?.let { throw exceptionFor(it) }
        val cursor = MatrixCursor(arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME))
        if (!behavior.present) return cursor
        val size: Long? = if (behavior.sizeUnknown) null else fileOf(id).length()
        cursor.addRow(arrayOf<Any?>(size, "shopledger-backup-$id.slcopy"))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = idOf(uri)
        val behavior = behaviors[id] ?: throw FileNotFoundException()
        behavior.openFailures[mode]?.let { throw exceptionFor(it) }
        if (!behavior.present) throw FileNotFoundException()
        val file = fileOf(id)
        if (behavior.pipe && mode.contains('w')) {
            val (read, write) = ParcelFileDescriptor.createReliablePipe()
            if (mode.contains('t')) file.writeBytes(ByteArray(0))
            drains += thread {
                ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                    FileOutputStream(file, true).use { input.copyTo(it) }
                }
            }
            return write
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun exceptionFor(failure: Failure): Exception = when (failure) {
        Failure.NOT_FOUND -> FileNotFoundException()

        Failure.UNSUPPORTED -> UnsupportedOperationException()

        Failure.SECURITY -> SecurityException()

        Failure.AUTHENTICATION -> {
            val context = requireNotNull(context)
            val intent = Intent(ACTION_SIGN_IN).setPackage(context.packageName)
            val action = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            AuthenticationRequiredException(IllegalStateException(), action)
        }

        Failure.OTHER -> IllegalStateException()
    }

    private fun fileOf(id: String): File = File(requireNotNull(context).cacheDir, "slot-test/$id")

    companion object {
        const val ACTION_SIGN_IN = "io.github.yesidrobayo.shopledger.data.test.SIGN_IN"

        val behaviors = ConcurrentHashMap<String, Behavior>()

        private val drains = java.util.Collections.synchronizedList(mutableListOf<Thread>())

        /** Espera a que las tuberías terminen de pasar al archivo. */
        fun awaitDrains() {
            synchronized(drains) { drains.toList() }.forEach { it.join(DRAIN_TIMEOUT_MILLIS) }
        }

        private const val DRAIN_TIMEOUT_MILLIS = 5_000L

        fun idOf(uri: Uri): String = requireNotNull(uri.lastPathSegment)

        /** La URI del documento [id], que existe vacío desde ahora. */
        fun create(cacheDir: File, id: String): Behavior {
            File(cacheDir, "slot-test").mkdirs()
            File(cacheDir, "slot-test/$id").writeBytes(ByteArray(0))
            return Behavior().also { behaviors[id] = it }
        }

        fun uriOf(authority: String, id: String): String = "content://$authority/document/$id"

        fun bytesOf(cacheDir: File, id: String): ByteArray =
            File(cacheDir, "slot-test/$id").readBytes()

        fun reset(cacheDir: File) {
            behaviors.clear()
            File(cacheDir, "slot-test").deleteRecursively()
        }
    }
}
