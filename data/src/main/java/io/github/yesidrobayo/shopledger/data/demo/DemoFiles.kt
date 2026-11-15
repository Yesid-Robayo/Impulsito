package io.github.yesidrobayo.shopledger.data.demo

import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.syncDirectory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Dónde vive el modo demostración, siempre aparte de la base del negocio.
 *
 * - **La marca** [marker], `demo-mode`, en `noBackupFilesDir`: mientras existe, la interfaz de
 *   este proceso usa la base de ejemplo. Es lo último que se escribe al entrar y lo primero que se
 *   borra al salir, así que un proceso que muere a mitad de cualquiera de los dos pasos vuelve a
 *   la base real, nunca a una base de ejemplo a medias.
 * - **La carpeta** [directory], `demo/`, también en `noBackupFilesDir`, una carpeta que el sistema
 *   nunca respalda ni transfiere. Guarda `demo.db` con sus archivos de SQLite, su propia marca de
 *   base creada y [seededMarker], que dice que los datos de ejemplo quedaron escritos enteros.
 *
 * Ninguna ruta coincide con la de la base del negocio, que vive en la carpeta de bases de la app:
 * borrar esta carpeta nunca la alcanza. Ninguna función lee el disco al crear la clase.
 *
 * @param noBackupDirectory la carpeta que el sistema no respalda; en la app, `noBackupFilesDir`.
 */
internal class DemoFiles(val noBackupDirectory: File) {
    /** La marca del modo demostración. Vacía: solo cuenta que exista. */
    val marker: File = File(noBackupDirectory, MARKER_NAME)

    /** La carpeta de la base de ejemplo. Se borra entera al salir. */
    val directory: File = File(noBackupDirectory, DIRECTORY_NAME)

    /** La base de ejemplo. */
    val database: File = File(directory, DATABASE_NAME)

    /** La marca de que los datos de ejemplo quedaron escritos enteros y la base, cerrada. */
    val seededMarker: File = File(directory, SEEDED_MARKER_NAME)

    init {
        require(database.name != DatabaseFiles.DATABASE_NAME) {
            "La base de ejemplo nunca se llama como la del negocio"
        }
    }

    /**
     * Los archivos de la base de ejemplo para la compuerta del arranque: su marca de base creada y
     * su copia previa a migrar viven en [directory], nunca junto a las de la base del negocio.
     */
    fun databaseFiles(): DatabaseFiles = DatabaseFiles(database.absoluteFile, directory)

    /**
     * Si hay una demostración lista: la marca, la base y sus datos de ejemplo enteros. Lee el
     * disco.
     */
    fun isComplete(): Boolean = marker.isFile && seededMarker.isFile && database.isFile

    /** Si queda algo de una demostración anterior, entera o a medias. Lee el disco. */
    fun hasLeftovers(): Boolean = marker.exists() || directory.exists()

    /**
     * Borra la demostración entera: primero la marca, para que ningún arranque vea una
     * demostración sin su base, y después la carpeta. Nunca toca nada fuera de estas dos rutas.
     */
    fun discard() {
        deactivate()
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("No se pudo borrar la carpeta de la demostración")
        }
    }

    /** Anota que los datos de ejemplo quedaron escritos enteros. Durable, como la marca. */
    fun markSeeded() = writeDurably(seededMarker)

    /**
     * Deja la marca: desde el arranque siguiente, la interfaz usa la base de ejemplo. Se llama
     * solo con la base de ejemplo entera y cerrada, y se sincroniza con el disco antes de volver.
     */
    fun activate() = writeDurably(marker)

    /** Borra la marca y lo sincroniza: el arranque siguiente usa la base real. */
    fun deactivate() {
        if (!marker.exists()) return
        if (!marker.delete() && marker.exists()) {
            throw IOException("No se pudo borrar la marca de la demostración")
        }
        syncDirectory(noBackupDirectory)
    }

    /** Prepara la carpeta vacía para una base de ejemplo nueva. */
    fun prepareDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("No se pudo crear la carpeta de la demostración")
        }
    }

    private fun writeDurably(file: File) {
        val parent = requireNotNull(file.parentFile) { "La marca siempre tiene carpeta" }
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("No se pudo crear la carpeta de la marca")
        }
        FileOutputStream(file).use { it.fd.sync() }
        syncDirectory(parent)
    }

    companion object {
        /** El nombre de la marca, en `noBackupFilesDir`. */
        const val MARKER_NAME = "demo-mode"

        /** La carpeta de la base de ejemplo, en `noBackupFilesDir`. */
        const val DIRECTORY_NAME = "demo"

        /** El nombre de la base de ejemplo. */
        const val DATABASE_NAME = "demo.db"

        /** La marca de datos de ejemplo enteros, dentro de [DIRECTORY_NAME]. */
        const val SEEDED_MARKER_NAME = "seeded"
    }
}
