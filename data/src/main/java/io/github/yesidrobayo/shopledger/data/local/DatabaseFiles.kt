package io.github.yesidrobayo.shopledger.data.local

import android.content.Context
import java.io.File

/**
 * Dónde vive la base del negocio. Es el único lugar que nombra su archivo: el respaldo, la
 * restauración y la exportación lo piden aquí, y así nunca tocan otra base por error.
 *
 * La comprobación de arranque llegará aquí también: si la app ya creó la base alguna vez y el
 * archivo falta, Room no debe abrirla, porque crearía una base vacía encima del negocio.
 */
internal object DatabaseFiles {
    /** El nombre del archivo de la base, en la carpeta de bases de la app. */
    const val DATABASE_NAME = "shopledger.db"

    /** La ruta absoluta de la base en [context]. No toca el disco. */
    fun databaseFile(context: Context): File = context.getDatabasePath(DATABASE_NAME).absoluteFile
}
