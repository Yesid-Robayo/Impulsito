package io.github.yesidrobayo.shopledger.data.backup

import java.io.File

/**
 * Las marcas de una restauración en curso, en `restore/` dentro de [directory], que en la app es
 * `noBackupFilesDir`: el cerrojo de la preparación y el diario del reemplazo.
 *
 * Mientras alguna exista, el respaldo no abre la base ni sube nada: la base puede estar a punto de
 * cambiar entera, y una copia de ese momento no sería ni la de antes ni la de después. La
 * restauración, o el arranque siguiente, las retira. Solo se leen: nunca se crean ni se borran aquí.
 *
 * Lee el disco: se llama fuera del hilo principal.
 */
internal class RestoreMarkers(private val directory: File) {
    /** Si hay una restauración en curso o a medias. */
    fun present(): Boolean {
        val restore = File(directory, RESTORE_DIRECTORY)
        return File(restore, IN_PROGRESS).exists() || File(restore, JOURNAL).exists()
    }

    companion object {
        const val RESTORE_DIRECTORY = "restore"

        /** El cerrojo de la preparación, con la app viva. */
        const val IN_PROGRESS = "in_progress"

        /** El diario del reemplazo de la base. */
        const val JOURNAL = "journal.json"
    }
}
