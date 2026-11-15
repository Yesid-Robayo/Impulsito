package io.github.yesidrobayo.shopledger.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlin.time.Instant

/**
 * La app instalada, como la ven la búsqueda de versión y su recordatorio.
 *
 * @property versionCode su número de compilación.
 * @property updatedAt cuándo se instaló o se actualizó por última vez en este teléfono.
 */
internal class InstalledPackage(val versionCode: Long, val updatedAt: Instant) {
    companion object {
        /** La app de [context], o nula si el sistema no la da. Es una llamada al sistema. */
        fun of(context: Context): InstalledPackage? {
            val manager = context.packageManager
            val info: PackageInfo =
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        manager.getPackageInfo(
                            context.packageName,
                            PackageManager.PackageInfoFlags.of(0),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        manager.getPackageInfo(context.packageName, 0)
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    return null
                }
            val versionCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            val updatedAt = Instant.fromEpochMilliseconds(info.lastUpdateTime)
            return InstalledPackage(versionCode, updatedAt)
        }
    }
}
