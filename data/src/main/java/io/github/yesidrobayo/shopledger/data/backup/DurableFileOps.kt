package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.syncDirectory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Se entera de cada operación terminada en disco. En la app no hace nada; las pruebas la usan para
 * matar el proceso justo después de una operación concreta.
 *
 * [operation] solo nombra archivos fijos de la app, nunca datos del negocio.
 */
internal fun interface OperationListener {
    fun afterOperation(operation: String)

    companion object {
        /** El de la app: no hace nada. */
        val NONE = OperationListener { }
    }
}

/**
 * Hace durable un cambio de nombres dentro de una carpeta: un renombrado no sobrevive a un corte de
 * energía hasta que la carpeta que lo contiene llega al disco.
 */
internal fun interface DirectorySync {
    fun sync(directory: File)

    companion object {
        /** El de la app: sincroniza la carpeta con el disco. */
        val SYSTEM = DirectorySync(::syncDirectory)
    }
}

/**
 * Las operaciones de archivo del reemplazo de la base. Cada una es atómica, queda en disco antes de
 * avisar a [listener], y se salta si no hay nada que hacer: repetirla tras una muerte del proceso es
 * seguro.
 */
internal class DurableFileOps(
    private val directorySync: DirectorySync = DirectorySync.SYSTEM,
    private val listener: OperationListener = OperationListener.NONE,
) {
    /**
     * Renombra [source] a [target], si [source] existe. Devuelve si lo hizo.
     *
     * Sin [replace], un [target] que ya existe detiene todo en vez de pisarlo: en el reemplazo eso
     * nunca debería pasar, y pisar una base es justo lo que no se puede deshacer.
     */
    fun move(source: File, target: File, replace: Boolean = false): Boolean {
        if (!source.exists()) return false
        if (!replace && target.exists()) {
            throw IOException("El destino ya existe: ${label(target)}")
        }
        val targetDirectory = parentOf(target)
        ensureDirectory(targetDirectory)
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val sourceDirectory = parentOf(source)
        directorySync.sync(sourceDirectory)
        if (sourceDirectory != targetDirectory) directorySync.sync(targetDirectory)
        listener.afterOperation("move ${label(source)} -> ${label(target)}")
        return true
    }

    /** Borra [file], si existe. Devuelve si lo hizo. */
    fun delete(file: File): Boolean {
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("No se pudo borrar ${label(file)}")
        directorySync.sync(parentOf(file))
        listener.afterOperation("delete ${label(file)}")
        return true
    }

    /**
     * Escribe [bytes] en [target] pasando por [temporary]: el temporal se sincroniza, se renombra
     * sobre el destino y se sincroniza la carpeta. Una muerte a mitad deja el destino anterior
     * entero, o el nuevo entero, nunca uno a medias. Son dos operaciones para [listener].
     */
    fun writeAtomically(target: File, temporary: File, bytes: ByteArray) {
        ensureDirectory(parentOf(target))
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        listener.afterOperation("write ${label(temporary)}")
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        directorySync.sync(parentOf(target))
        listener.afterOperation("rename ${label(temporary)} -> ${label(target)}")
    }

    /**
     * Copia [source] en [target] pasando por [temporary], con la misma durabilidad que
     * [writeAtomically]: nunca queda un [target] a medias. Lee y escribe en flujo.
     */
    fun copyAtomically(source: File, target: File, temporary: File) {
        ensureDirectory(parentOf(target))
        FileInputStream(source).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output, BUFFER_BYTES)
                output.fd.sync()
            }
        }
        listener.afterOperation("copy ${label(source)} -> ${label(temporary)}")
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        directorySync.sync(parentOf(target))
        listener.afterOperation("rename ${label(temporary)} -> ${label(target)}")
    }

    /** Crea [file] vacío y durable, si no existe. Devuelve si lo creó. */
    fun createMarker(file: File): Boolean {
        if (file.exists()) return false
        ensureDirectory(parentOf(file))
        FileOutputStream(file).use { it.fd.sync() }
        directorySync.sync(parentOf(file))
        listener.afterOperation("create ${label(file)}")
        return true
    }

    /**
     * Crea la carpeta y las que falten por encima, y sincroniza cada padre. No cuenta como
     * operación: una carpeta vacía no cambia qué base está dónde.
     */
    fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        ensureDirectory(parentOf(directory))
        if (!directory.mkdir() && !directory.isDirectory) {
            throw IOException("No se pudo crear ${directory.name}")
        }
        directorySync.sync(parentOf(directory))
    }

    private fun parentOf(file: File): File = requireNotNull(file.absoluteFile.parentFile) {
        "Un archivo de la app siempre tiene carpeta"
    }

    /** Carpeta y nombre, sin la ruta completa: basta para saber qué archivo fue. */
    private fun label(file: File): String = "${file.parentFile?.name}/${file.name}"

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
