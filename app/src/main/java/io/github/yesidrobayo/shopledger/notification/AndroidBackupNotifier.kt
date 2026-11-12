package io.github.yesidrobayo.shopledger.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.data.BackupNotification
import io.github.yesidrobayo.shopledger.data.BackupNotifier
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider

/**
 * Las notificaciones del respaldo, en el canal del respaldo y en el idioma de la interfaz, aunque
 * las lance un trabajo con la app cerrada.
 *
 * - **Sin datos del negocio.** Ninguna lleva montos, nombres ni cantidades: con el teléfono
 *   bloqueado se pueden ver enteras.
 * - **Tocarla abre la app**, con un aviso explícito hacia la actividad principal que nadie más
 *   puede cambiar.
 * - **Una por tipo.** Cada tipo tiene su número fijo: una nueva reemplaza a la anterior del mismo
 *   tipo, en vez de apilarse.
 * - **Dice si salió.** Sin el permiso de avisos, o con el canal silenciado por la persona, no sale y
 *   responde `false`: quien la pidió no la cuenta como lanzada.
 *
 * Desde Android 13 hace falta el permiso de avisos, que la app pide en contexto. Sin él, el sistema
 * no deja salir ninguna, y esta clase lo dice con `false`, sin fallar: mira antes si los avisos
 * están permitidos, y atrapa el rechazo del sistema si cambió entre una cosa y la otra.
 *
 * @param app el contexto de la aplicación.
 * @param texts da el contexto con el idioma de la interfaz.
 */
class AndroidBackupNotifier(
    private val app: Context,
    private val texts: LocalizedContextProvider,
) : BackupNotifier {
    override fun show(notification: BackupNotification): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (!canPost(manager)) return false
        val context = texts.context()
        val content = notification.content()
        val built =
            NotificationCompat
                .Builder(app, NotificationChannels.BACKUP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(content.title, *content.titleArgs))
                .setContentText(context.getString(content.body))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // No lleva datos del negocio: se puede ver entera con el teléfono bloqueado.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        return try {
            post(manager, content.id, built)
            true
        } catch (_: SecurityException) {
            // Desde Android 13, sin el permiso de avisos el sistema la rechaza.
            false
        }
    }

    // El permiso se comprueba antes, con areNotificationsEnabled, y su rechazo se atrapa al llamar.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, id: Int, notification: Notification) {
        manager.notify(TAG, id, notification)
    }

    /** Si los avisos de la app y el canal del respaldo están permitidos. */
    private fun canPost(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(NotificationChannels.BACKUP_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    private fun openApp(): PendingIntent {
        val intent =
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            app,
            OPEN_APP_REQUEST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        /** La etiqueta de las notificaciones del respaldo, para no chocar con las de otro canal. */
        const val TAG = "backup"

        /** El pedido del aviso que abre la app. */
        const val OPEN_APP_REQUEST = 1
    }
}

/**
 * El número fijo y los textos de una notificación.
 *
 * @property titleArgs los valores del título, como el nombre de la nube, que no se traducen.
 */
private class BackupNotificationContent(
    val id: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val titleArgs: Array<Any> = emptyArray(),
)

/** Los textos de cada notificación. Una nueva sin textos no compila. */
private fun BackupNotification.content(): BackupNotificationContent = when (this) {
    BackupNotification.Stale ->
        BackupNotificationContent(
            id = 1,
            title = R.string.notif_backup_stale_title,
            body = R.string.notif_backup_stale_body,
        )

    // Sin el código ni nada del negocio: solo pide comprobarlo.
    BackupNotification.RecoveryCheckDue ->
        BackupNotificationContent(
            id = 3,
            title = R.string.notif_recovery_check_title,
            body = R.string.notif_recovery_check_body,
        )

    is BackupNotification.CheckDue ->
        BackupNotificationContent(
            id = 2,
            title = R.string.notif_backup_check_title,
            body = R.string.notif_backup_check_body,
            titleArgs = arrayOf(providerLabel),
        )
}
