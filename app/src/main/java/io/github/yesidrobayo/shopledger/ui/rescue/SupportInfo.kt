package io.github.yesidrobayo.shopledger.ui.rescue

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.pm.PackageInfoCompat
import io.github.yesidrobayo.shopledger.R

/**
 * Los datos técnicos de `Información para soporte`, en el rescate y en Ajustes: la versión de la
 * app y de Android, el teléfono, la última copia y el último código de error. Sin la base, como en
 * el rescate, no hay estado de la última copia, y se dice. Nunca lleva montos, cantidades, nombres,
 * notas, ni la dirección, el usuario o la contraseña del destino.
 *
 * @property appVersion el nombre y el código de la versión instalada.
 * @property android la versión de Android y su nivel de API.
 * @property device el fabricante y el modelo.
 * @property lastError el código estable del último error, o `null` si no hay ninguno.
 * @property lastBackup cuándo fue la última copia, ya escrita con el formato regional, o `null`.
 * @property handoff con la nube del teléfono la copia se entrega, no se confirma: la línea dice
 *   `Última entrega`.
 */
data class SupportFacts(
    val appVersion: String,
    val android: String,
    val device: String,
    val lastError: String?,
    val lastBackup: String? = null,
    val handoff: Boolean = false,
) {
    /** Las líneas de la hoja, con las etiquetas en el idioma de [context]. */
    fun lines(context: Context): List<SupportLine> {
        val unavailable = context.getString(R.string.support_unavailable)
        return listOf(
            SupportLine(
                context.getString(
                    R.string.support_field_version,
                    context.getString(R.string.app_name),
                ),
                appVersion,
            ),
            SupportLine(context.getString(R.string.support_field_android), android),
            SupportLine(context.getString(R.string.support_field_device), device),
            SupportLine(
                context.getString(
                    if (handoff) {
                        R.string.support_field_last_handoff
                    } else {
                        R.string.support_field_last_backup
                    },
                ),
                lastBackup ?: unavailable,
            ),
            SupportLine(
                context.getString(R.string.support_field_last_error),
                lastError ?: unavailable,
            ),
        )
    }

    companion object {
        /** Los datos de este teléfono para [cause]. Lee el sistema: va fuera del hilo principal. */
        fun of(context: Context, cause: RescueReason): SupportFacts = ofDevice(context, cause.code)

        /**
         * Los datos de este teléfono, con [lastError] y, si se sabe, la última copia. Lee el
         * sistema: va fuera del hilo principal.
         */
        fun ofDevice(
            context: Context,
            lastError: String?,
            lastBackup: String? = null,
            handoff: Boolean = false,
        ): SupportFacts = SupportFacts(
            appVersion = appVersionOf(context),
            android = "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            lastError = lastError,
            lastBackup = lastBackup,
            handoff = handoff,
        )
    }
}

/**
 * La versión y el número de compilación de la app, como `1.2.0 (12)`. Lee el sistema: va fuera del
 * hilo principal.
 */
fun appVersionOf(context: Context): String {
    val manager = context.packageManager
    val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
    return "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
}

/** El código estable de cada causa, en inglés, para soporte: no es un texto para la persona. */
val RescueReason.code: String
    get() = when (this) {
        RescueReason.DatabaseMissing -> "DATABASE_MISSING"
        RescueReason.NewerVersion -> "DATABASE_FROM_NEWER_APP"
        RescueReason.OpenFailed -> "DATABASE_OPEN_FAILED"
        RescueReason.MigrationFailed -> "MIGRATION_FAILED"
        RescueReason.PreMigrationCopyFailed -> "PRE_MIGRATION_COPY_FAILED"
        is RescueReason.NotEnoughSpace -> "PRE_MIGRATION_NO_SPACE"
        RescueReason.RestoreJournalUnreadable -> "RESTORE_JOURNAL_UNREADABLE"
        RescueReason.RestoreLeftNoDatabase -> "RESTORE_LEFT_NO_DATABASE"
        RescueReason.SafeMode -> "SAFE_MODE"
    }

/**
 * El texto que se copia: una línea por dato. [line] une etiqueta y valor con el recurso del idioma
 * activo, porque la puntuación entre los dos también cambia entre idiomas.
 */
fun supportText(lines: List<SupportLine>, line: (label: String, value: String) -> String): String =
    lines.joinToString(separator = "\n") { line(it.label, it.value) }

/**
 * Copia [text] al portapapeles. No es un dato sensible, pero desde Android 13 se marca igual para
 * que la vista previa del sistema no lo muestre a quien mire la pantalla.
 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras =
            PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    manager.setPrimaryClip(clip)
}
