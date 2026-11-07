package io.github.yesidrobayo.shopledger.data.backup

import android.content.Context
import java.io.File

/**
 * La carpeta privada donde se arma cada copia antes de salir del teléfono, y los nombres de sus
 * archivos.
 *
 * Vive en `noBackupFilesDir`: es privada de la app, el sistema nunca la respalda ni la transfiere
 * a otro teléfono y, a diferencia de la caché, no la limpia a mitad de una subida. Está en el mismo
 * sistema de archivos que la base, así que mover un archivo entre las dos es un renombrado.
 *
 * Los nombres son fijos y salen solo de la generación de la copia, nunca de un nombre que venga del
 * destino: así nada que llegue de afuera puede elegir dónde se escribe.
 *
 * @property directory la carpeta, que se crea al escribir el primer archivo.
 */
internal class BackupStaging(val directory: File) {
    /**
     * La salida de la instantánea de la generación [generation], como `snapshot-g00000042.db`.
     * No toca el disco.
     */
    fun snapshotFile(generation: Long): File =
        File(directory, "$SNAPSHOT_PREFIX${generationText(generation)}$DATABASE_SUFFIX")

    companion object {
        /** El nombre de la carpeta dentro de `noBackupFilesDir`. */
        const val DIRECTORY_NAME = "backup-staging"

        private const val SNAPSHOT_PREFIX = "snapshot-g"
        private const val DATABASE_SUFFIX = ".db"

        /** Cifras mínimas de la generación en el nombre: así los nombres se ordenan como números. */
        private const val GENERATION_DIGITS = 8

        /**
         * La de la app, en `noBackupFilesDir`. Pedir esa carpeta toca el disco, porque Android la
         * crea si falta: se llama fuera del hilo principal.
         */
        fun inNoBackupDir(context: Context): BackupStaging =
            BackupStaging(File(context.noBackupFilesDir, DIRECTORY_NAME))

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
