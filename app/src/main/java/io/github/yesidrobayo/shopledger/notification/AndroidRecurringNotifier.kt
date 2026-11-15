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
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.port.RecurringReminder
import io.github.yesidrobayo.shopledger.domain.port.RecurringReminderNotifier
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest

/**
 * Los recordatorios de gastos recurrentes por confirmar, en su canal y en el idioma de la
 * interfaz.
 *
 * - **Sin montos, nunca.** El aviso dice qué gasto espera confirmación, no cuánto es.
 * - **Discretos con el teléfono bloqueado.** Cada aviso es privado, y su versión pública solo dice
 *   que hay un gasto por confirmar. Con el modo discreto activo, la versión privada tampoco lo
 *   nombra.
 * - **Tocarlo abre los gastos recurrentes**, con un aviso explícito e inmutable hacia la actividad
 *   principal que no lleva ningún dato. Avisar no registra nada: se confirma en la pantalla.
 * - **Dice si salió.** Sin el permiso de avisos, con los avisos de la app apagados o con el canal
 *   silenciado, no sale y responde `false`, sin fallar.
 *
 * @param app el contexto de la aplicación.
 * @param texts da el contexto con el idioma de la interfaz.
 * @param settings lee los ajustes vigentes, para el modo discreto.
 */
class AndroidRecurringNotifier(
    private val app: Context,
    private val texts: LocalizedContextProvider,
    private val settings: suspend () -> AppSettings,
) : RecurringReminderNotifier {
    override suspend fun show(reminders: List<RecurringReminder>): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (reminders.isEmpty() || !canPost(manager)) return false
        val discreet = settings().preferences.discreetModeEnabled
        val context = texts.context()
        val built = reminders.map { it to single(it, context, discreet) }
        return try {
            for ((reminder, notification) in built) {
                post(manager, reminder.recurring.id.toInt(), notification)
            }
            if (reminders.size > 1) post(manager, SUMMARY_ID, summary(reminders.size, context))
            true
        } catch (_: SecurityException) {
            // Desde Android 13, sin el permiso de avisos el sistema lo rechaza.
            false
        }
    }

    private fun single(
        reminder: RecurringReminder,
        context: Context,
        discreet: Boolean,
    ): Notification {
        val title = context.getString(R.string.notif_recurring_title)
        if (discreet) {
            return builder(context.getString(R.string.notif_recurring_public))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }
        val body = context.getString(
            R.string.notif_recurring_body,
            nameOf(reminder, context),
            context.getString(R.string.app_name),
        )
        return builder(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                builder(context.getString(R.string.notif_recurring_public))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )
            .build()
    }

    /** La descripción del gasto, o el nombre de su categoría si no tiene. */
    private fun nameOf(reminder: RecurringReminder, context: Context): String {
        reminder.recurring.details.description?.let { return it }
        val category =
            reminder.category ?: return context.getString(R.string.notif_recurring_title)
        return SeedText.displayName(
            context.resources,
            SeedTable.EXPENSE_CATEGORIES,
            category.systemKey,
            category.details.name,
        )
    }

    /** El resumen de varios avisos: solo dice cuántos, así que se ve con el teléfono bloqueado. */
    private fun summary(count: Int, context: Context): Notification {
        val title =
            context.resources.getQuantityString(R.plurals.notif_recurring_many_title, count, count)
        return builder(title)
            .setContentText(context.getString(R.string.notif_lot_many_body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroupSummary(true)
            .build()
    }

    private fun builder(title: String): NotificationCompat.Builder = NotificationCompat
        .Builder(app, NotificationChannels.RECURRING_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setGroup(GROUP)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .setContentIntent(open())

    private fun open(): PendingIntent {
        val request = AppOpenRequest.Recurring
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

    /** Si los avisos de la app y el canal de gastos recurrentes están permitidos. */
    private fun canPost(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel =
            manager.getNotificationChannelCompat(NotificationChannels.RECURRING_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // El permiso se comprueba antes, con areNotificationsEnabled; su rechazo se atrapa al llamar.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, id: Int, notification: Notification) {
        manager.notify(TAG, id, notification)
    }

    private companion object {
        /** La etiqueta de estos avisos, para no chocar con los de stock bajo ni vencimientos. */
        const val TAG = "recurring"

        /** El grupo de los recordatorios de gastos recurrentes. */
        const val GROUP = "recurring"

        /** El número fijo del resumen. Ningún gasto recurrente tiene el id 0. */
        const val SUMMARY_ID = 0
    }
}
