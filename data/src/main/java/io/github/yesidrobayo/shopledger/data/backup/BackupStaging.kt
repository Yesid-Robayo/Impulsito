package io.github.yesidrobayo.shopledger.data.backup

import android.content.Context
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.withContext

/**
 * La carpeta privada donde se arma cada copia antes de salir del teléfono, los nombres de sus
 * archivos y su limpieza.
 *
 * Vive en `noBackupFilesDir`: es privada de la app, el sistema nunca la respalda ni la transfiere
 * a otro teléfono y, a diferencia de la caché, no la limpia a mitad de una subida. Está en el mismo
 * sistema de archivos que la base, así que mover un archivo entre las dos es un renombrado.
 *
 * Los nombres son fijos y salen solo de la generación de la copia, nunca de un nombre que venga del
 * destino: así nada que llegue de afuera puede elegir dónde se escribe.
 *
 * @property directory la carpeta, que se crea al escribir el primer archivo.
 * @param ioContext contexto de la limpieza, fuera del hilo principal.
 */
internal class BackupStaging(val directory: File, private val ioContext: CoroutineContext) {
    /**
     * La salida de la instantánea de la generación [generation], como `snapshot-g00000042.db`.
     * No toca el disco.
     */
    fun snapshotFile(generation: Long): File = file(generation, DATABASE_SUFFIX)

    /**
     * La copia comprimida de la generación [generation], la que se sube, como
     * `snapshot-g00000042.db.gz`. No toca el disco.
     */
    fun compressedFile(generation: Long): File = file(generation, COMPRESSED_SUFFIX)

    /**
     * Los metadatos de la copia de la generación [generation], como `snapshot-g00000042.slmeta`.
     * Quedan aquí hasta confirmar la copia: si una subida se corta entre el archivo y sus
     * metadatos, se suben estos, sin generar otra copia. No toca el disco.
     */
    fun metaFile(generation: Long): File = file(generation, META_SUFFIX)

    /**
     * Borra todo lo que quedó en la carpeta, salvo la copia comprimida de una subida pendiente y sus
     * metadatos.
     *
     * Un temporal del respaldo no debe quedar en el teléfono más de lo necesario. La única
     * excepción es la copia comprimida de [pendingGeneration], la generación que espera un
     * reintento de subida, con sus metadatos: repetir la subida con el mismo archivo evita generar
     * otra copia. Cada uno se conserva solo si tiene menos de [PENDING_UPLOAD_MAX_AGE]. Si el reloj
     * dice que es del futuro, se borra: un reloj que retrocedió no puede conservarlo para siempre,
     * y borrarlo solo obliga a generar otra copia.
     *
     * Solo borra archivos. Las subcarpetas son de otros trabajos, que las limpian ellos mismos.
     *
     * @param pendingGeneration la generación que espera un reintento, o `null` si no hay ninguna.
     * @param now el instante del reloj del teléfono, con que se mide la edad del archivo.
     */
    suspend fun cleanUp(pendingGeneration: Long?, now: Instant): Unit = withContext(ioContext) {
        val kept =
            pendingGeneration
                ?.let { listOf(compressedFile(it), metaFile(it)) }
                .orEmpty()
                .filter { isFresh(it, now) }
        for (file in directory.listFiles().orEmpty()) {
            if (file.isFile && file !in kept) file.delete()
        }
    }

    private fun isFresh(file: File, now: Instant): Boolean {
        if (!file.isFile) return false
        val age = now - Instant.fromEpochMilliseconds(file.lastModified())
        return !age.isNegative() && age < PENDING_UPLOAD_MAX_AGE
    }

    private fun file(generation: Long, suffix: String): File =
        File(directory, "$SNAPSHOT_PREFIX${generationText(generation)}$suffix")

    companion object {
        /** El nombre de la carpeta dentro de `noBackupFilesDir`. */
        const val DIRECTORY_NAME = "backup-staging"

        /** Lo más que se conserva la copia comprimida de una subida pendiente. */
        val PENDING_UPLOAD_MAX_AGE = 24.hours

        private const val SNAPSHOT_PREFIX = "snapshot-g"
        private const val DATABASE_SUFFIX = ".db"
        private const val COMPRESSED_SUFFIX = ".db.gz"
        private const val META_SUFFIX = ".slmeta"

        /** Cifras mínimas de la generación en el nombre: así los nombres se ordenan como números. */
        private const val GENERATION_DIGITS = 8

        /**
         * La de la app, en `noBackupFilesDir`. Pedir esa carpeta toca el disco, porque Android la
         * crea si falta: se llama fuera del hilo principal.
         */
        fun inNoBackupDir(context: Context, ioContext: CoroutineContext): BackupStaging =
            BackupStaging(File(context.noBackupFilesDir, DIRECTORY_NAME), ioContext)

        /**
         * La generación con al menos [GENERATION_DIGITS] cifras ASCII, rellenas con ceros a la
         * izquierda. No pasa por el formato del idioma, que podría escribir otras cifras.
         */
        internal fun generationText(generation: Long): String {
            require(generation > 0) { "La generación de una copia empieza en 1" }
            return generation.toString().padStart(GENERATION_DIGITS, '0')
        }
    }
}
