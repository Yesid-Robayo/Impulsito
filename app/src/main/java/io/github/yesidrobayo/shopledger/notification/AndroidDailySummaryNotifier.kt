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
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.DailySummary
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf

/**
 * La notificación del resumen del día, en su canal y en el idioma de la interfaz.
 *
 * - **Sin montos con el teléfono bloqueado.** Es privada, y su versión pública solo dice que el
 *   resumen está listo. Con el modo discreto activo, la privada tampoco lleva montos: solo cuántas
 *   ventas hubo.
 * - **Tocarla abre la app en Inicio**, con un aviso explícito e inmutable que no lleva datos.
 * - **Dice si salió.** Sin el permiso de avisos, con los avisos de la app apagados o con su canal
 *   silenciado, no sale y responde `false`, sin fallar.
 *
 * @param app el contexto de la aplicación.
 * @param texts da el contexto con el idioma de la interfaz.
 */
class AndroidDailySummaryNotifier(
    private val app: Context,
    private val texts: LocalizedContextProvider,
) {
    /** Muestra [summary], con el formato de [regional]. */
    fun show(summary: DailySummary, regional: RegionalSettings): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (!canPost(manager)) return false
        val context = texts.context()
        val count = summary.salesCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val formats = businessFormatsOf(regional, context.resources.configuration.locales[0])
        val countText = formats.quantity.formatCount(summary.salesCount)
        val body =
            if (summary.discreet) {
                context.getString(R.string.notif_daily_body_discreet, countText)
            } else {
                context.getString(
                    R.string.notif_daily_body,
                    countText,
                    money(summary.netSales, formats, context),
                    money(summary.grossProfit, formats, context),
                )
            }
        val notification =
            builder(context.getString(R.string.notif_daily_title))
                .setContentText(body)
                .setNumber(count)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context))
                .setContentIntent(open())
                .build()
        return try {
            post(manager, notification)
            true
        } catch (_: SecurityException) {
            // Desde Android 13, sin el permiso de avisos el sistema lo rechaza.
            false
        }
    }

    /** [amount] con su símbolo, en el orden de la región, como en la pantalla. */
    private fun money(amount: Money, formats: AppFormats, context: Context): String {
        val parts = formats.money.parts(amount)
        val chain = CurrencySymbolFonts.chain(context.resources)
        val symbol = chain.resolve(parts.symbol, parts.currencyCode)
        return moneyText(parts, symbol).text
    }

    /** Lo único que se ve con el teléfono bloqueado: que el resumen está listo, sin cifras. */
    private fun publicVersion(context: Context): Notification =
        builder(context.getString(R.string.notif_daily_public))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun builder(title: String): NotificationCompat.Builder = NotificationCompat
        .Builder(app, NotificationChannels.DAILY_SUMMARY_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setAutoCancel(true)

    /** Abre la app sin pedir ninguna pantalla: arranca en Inicio. */
    private fun open(): PendingIntent {
        val intent =
            Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        return PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Si los avisos de la app y el canal del resumen están permitidos. */
    private fun canPost(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel =
            manager.getNotificationChannelCompat(NotificationChannels.DAILY_SUMMARY_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // El permiso se mira antes, con areNotificationsEnabled, y su rechazo se atrapa al llamar.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, notification: Notification) {
        manager.notify(TAG, ID, notification)
    }

    private companion object {
        /** La etiqueta del resumen, para no chocar con los demás avisos. */
        const val TAG = "daily_summary"

        /** Un solo resumen a la vez: el de mañana reemplaza al de hoy. */
        const val ID = 1

        /**
         * El código del aviso que abre la app. Es negativo para no coincidir con el de un aviso de
         * producto, que usa su id.
         */
        const val REQUEST_CODE = -1
    }
}
