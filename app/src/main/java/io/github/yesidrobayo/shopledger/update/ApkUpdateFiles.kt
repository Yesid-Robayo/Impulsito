package io.github.yesidrobayo.shopledger.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import java.io.File

/**
 * El APK que bajó `Actualizar`: vive en `cache/apk-update/`, con un nombre fijo que nunca viene de
 * la red, y sale solo hacia el instalador del sistema, por el proveedor de archivos y con permiso
 * de lectura.
 *
 * Se borra al fallar, al cancelar, al salir de la pantalla sin instalar y al abrir la app de nuevo,
 * pero nunca mientras el instalador lo tiene abierto.
 */
internal object ApkUpdateFiles {
    /** La carpeta dentro de la caché. Coincide con el camino del proveedor de archivos. */
    const val FOLDER = "apk-update"

    /** El nombre fijo del archivo. */
    const val FILE_NAME = "update.apk"

    /** El tipo con que el instalador del sistema reconoce un APK. */
    const val APK_TYPE = "application/vnd.android.package-archive"

    /** El archivo. Pedirlo no toca el disco. */
    fun file(context: Context): File = File(File(context.cacheDir, FOLDER), FILE_NAME)

    /** Borra la carpeta entera, también restos de una descarga cortada. Toca el disco. */
    fun clear(context: Context) {
        File(context.cacheDir, FOLDER).deleteRecursively()
    }

    /**
     * Abre el instalador del sistema con el archivo, desde [activity]. Android muestra su propia
     * confirmación para actualizar la app, y él decide. Devuelve `false` si no hay instalador o el
     * archivo no se puede compartir.
     */
    fun openInstaller(activity: Context): Boolean = try {
        val authority = ExportFolder.authority(activity)
        val uri = FileProvider.getUriForFile(activity, authority, file(activity))
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: IllegalArgumentException) {
        // El proveedor no comparte ese camino.
        false
    }

    /**
     * Abre en los ajustes de Android el permiso de instalar actualizaciones desde la app, solo para
     * su paquete. Devuelve `false` si el teléfono no tiene esa pantalla.
     */
    fun openInstallPermission(activity: Context): Boolean = try {
        val intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts(PACKAGE_SCHEME, activity.packageName, null),
            )
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private const val PACKAGE_SCHEME = "package"
}
