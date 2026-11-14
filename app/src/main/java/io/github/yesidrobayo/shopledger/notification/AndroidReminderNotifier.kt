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
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotice
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotifier
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf

/**
 * Los recordatorios de la app que no son de stock bajo ni del respaldo. Hoy, los avisos de
 * vencimiento de los lotes, en el canal de vencimientos y en el idioma de la interfaz.
 *
 * - **Discretos con el teléfono bloqueado.** Cada aviso es privado, y su versión pública dice solo
 *   que un producto está por vencer, sin nombre, lote ni cantidad. Con el modo discreto activo, la
 *   versión privada tampoco los lleva.
 * - **Tocarlo abre la lista `Por vencer`** del inventario, con un aviso explícito e inmutable hacia
 *   la actividad principal que no lleva ningún dato. Varios a la vez van en un grupo con su resumen.
 * - **Dice si salió.** Sin el permiso de avisos, con los avisos de la app apagados o con el canal
 *   de vencimientos silenciado, no sale y responde `false`, sin fallar.
 *
 * @param app el contexto de la aplicación.
 * @param texts da el contexto con el idioma de la interfaz.
 * @param settings lee los ajustes vigentes: el formato de fechas y cantidades y el modo discreto.
 */
class AndroidReminderNotifier(
    private val app: Context,
    private val texts: LocalizedContextProvider,
    private val settings: suspend () -> AppSettings,
) : ExpiryNotifier {
    override suspend fun show(notices: List<ExpiryNotice>): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (notices.isEmpty() || !canPost(manager)) return false
        val current = settings()
        val context = texts.context()
        val formats =
            businessFormatsOf(current.regional, context.resources.configuration.locales[0])
        val discreet = current.preferences.discreetModeEnabled
        val built = notices.map { it to single(it, context, formats, discreet) }
        return try {
            for ((notice, notification) in built) {
                post(manager, notice.lot.lot.lotId.toInt(), notification)
            }
            if (notices.size > 1) post(manager, SUMMARY_ID, summary(notices.size, context))
            true
        } catch (_: SecurityException) {
            // Desde Android 13, sin el permiso de avisos el sistema lo rechaza.
            false
        }
    }

    private fun single(
        notice: ExpiryNotice,
        context: Context,
        formats: AppFormats,
        discreet: Boolean,
    ): Notification {
        val open = open()
        if (discreet) {
            return builder(context.getString(R.string.notif_lot_public_title))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .build()
        }
        val lot = notice.lot.lot
        val date = formats.date.dayMonthYear(lot.expiryDate)
        val left =
            AndroidLowStockNotifier.quantityText(lot.onHand, notice.unit, formats.quantity, context)
        val (title, body) =
            if (notice.lot.isExpired) {
                R.string.notif_lot_expired_title to R.string.notif_lot_expired_body
            } else {
                R.string.notif_lot_expiring_title to R.string.notif_lot_expiring_body
            }
        return builder(context.getString(title, lot.productName))
            .setContentText(context.getString(body, date, left))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context))
            .setContentIntent(open)
            .build()
    }

    /** El resumen de varios avisos: solo dice cuántos, así que se ve con el teléfono bloqueado. */
    private fun summary(count: Int, context: Context): Notification =
        builder(context.resources.getQuantityString(R.plurals.notif_lot_many_title, count, count))
            .setContentText(context.getString(R.string.notif_lot_many_body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroupSummary(true)
            .setContentIntent(open())
            .build()

    /** Lo único que se ve con el teléfono bloqueado. Al tocarla se abre el aviso entero. */
    private fun publicVersion(context: Context): Notification =
        builder(context.getString(R.string.notif_lot_public_title))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun builder(title: String): NotificationCompat.Builder = NotificationCompat
        .Builder(app, NotificationChannels.EXPIRY_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setGroup(GROUP)
        .setAutoCancel(true)

    private fun open(): PendingIntent {
        val request = AppOpenRequest.Expiring
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

    /** Si los avisos de la app y el canal de vencimientos están permitidos. */
    private fun canPost(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(NotificationChannels.EXPIRY_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // El permiso se comprueba antes, con areNotificationsEnabled, y su rechazo se atrapa al llamar.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, id: Int, notification: Notification) {
        manager.notify(TAG, id, notification)
    }

    private companion object {
        /** La etiqueta de los avisos de vencimiento, para no chocar con los de stock bajo. */
        const val TAG = "expiry"

        /** El grupo de los avisos de vencimiento. */
        const val GROUP = "expiry"

        /** El número fijo del resumen. Ningún lote tiene el id 0. */
        const val SUMMARY_ID = 0
    }
}
