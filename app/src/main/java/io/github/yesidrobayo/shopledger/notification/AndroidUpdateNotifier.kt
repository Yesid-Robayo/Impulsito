package io.github.yesidrobayo.shopledger.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest

/**
 * La notificación de una versión nueva, en el canal `Actualizaciones` y en el idioma de la
 * interfaz, aunque la lance la búsqueda automática con la app cerrada.
 *
 * - **Solo informa.** Dice el nombre de la versión y nada del negocio: se ve entera con el teléfono
 *   bloqueado. Tocarla abre `Actualizaciones y verificación de APK`, que busca de nuevo y ofrece
 *   `Actualizar`; descargar e instalar sigue siendo solo al tocar ese botón.
 * - **Una sola a la vez.** Tiene un número fijo: la de una versión más nueva reemplaza a la
 *   anterior.
 * - **Dice si salió.** Sin el permiso de avisos, o con el canal silenciado, responde `false`, sin
 *   fallar.
 *
 * @param app el contexto de la aplicación.
 * @param texts da el contexto con el idioma de la interfaz.
 */
class AndroidUpdateNotifier(
    private val app: Context,
    private val texts: LocalizedContextProvider,
) {
    /** Lanza el aviso de [update]. `true` si el sistema lo aceptó. */
    fun show(update: KnownUpdate): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (!canPost(manager)) return false
        val context = texts.context()
        val built =
            NotificationCompat
                .Builder(app, NotificationChannels.UPDATES_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_update_new_title))
                .setContentText(
                    context.getString(
                        R.string.notif_update_new_body,
                        update.versionName,
                        context.getString(R.string.app_name),
                    ),
                ).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // Solo lleva el nombre de la versión: se puede ver con el teléfono bloqueado.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setContentIntent(open())
                .setAutoCancel(true)
                .build()
        return try {
            post(manager, built)
            true
        } catch (_: SecurityException) {
            // Desde Android 13, sin el permiso de avisos el sistema la rechaza.
            false
        }
    }

    private fun open(): PendingIntent {
        val request = AppOpenRequest.Updates
        val intent =
            Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        request.writeTo(intent)
        return PendingIntent.getActivity(
            app,
            request.requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Si los avisos de la app y el canal de actualizaciones están permitidos. */
    private fun canPost(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(NotificationChannels.UPDATES_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // El permiso se comprueba antes, con areNotificationsEnabled; su rechazo se atrapa al llamar.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, notification: Notification) {
        manager.notify(TAG, NOTIFICATION_ID, notification)
    }

    private companion object {
        /** La etiqueta de este aviso, para no chocar con los de otro canal. */
        const val TAG = "updates"

        /** El número fijo del aviso: uno nuevo reemplaza al anterior. */
        const val NOTIFICATION_ID = 1
    }
}
