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
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotice
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotifier
import io.github.yesidrobayo.shopledger.format.IcuTextNormalizer
import io.github.yesidrobayo.shopledger.format.QuantityFormatter
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf

/**
 * Los avisos de stock bajo, en el canal de stock y en el idioma de la interfaz.
 *
 * - **Discretos con el teléfono bloqueado.** Cada aviso es privado, y su versión pública dice solo
 *   que un producto está por agotarse, sin nombre ni cantidad. Con el modo discreto activo, la
 *   versión privada tampoco los lleva.
 * - **Tocarlo abre el producto**, con un aviso explícito e inmutable hacia la actividad principal
 *   que solo lleva su número. Varios a la vez van en un grupo, y su resumen abre la lista de lo que
 *   se está acabando.
 * - **Dice si salió.** Sin el permiso de avisos, con los avisos de la app apagados o con el canal
 *   de stock silenciado, no sale y responde `false`, sin fallar: la alerta queda bloqueada.
 *
 * @param app el contexto de la aplicación.
 * @param texts da el contexto con el idioma de la interfaz.
 * @param settings lee los ajustes vigentes: el formato de las cantidades y el modo discreto.
 */
class AndroidLowStockNotifier(
    private val app: Context,
    private val texts: LocalizedContextProvider,
    private val settings: suspend () -> AppSettings,
) : LowStockNotifier {
    override suspend fun show(notices: List<LowStockNotice>): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (notices.isEmpty() || !canPost(manager)) return false
        val current = settings()
        val context = texts.context()
        val quantities =
            businessFormatsOf(current.regional, context.resources.configuration.locales[0])
                .quantity
        val discreet = current.preferences.discreetModeEnabled
        val built = notices.map { it to single(it, context, quantities, discreet) }
        return try {
            for ((notice, notification) in built) {
                post(manager, notice.productId.toInt(), notification)
            }
            if (notices.size > 1) post(manager, SUMMARY_ID, summary(notices.size, context))
            true
        } catch (_: SecurityException) {
            // Desde Android 13, sin el permiso de avisos el sistema lo rechaza.
            false
        }
    }

    private fun single(
        notice: LowStockNotice,
        context: Context,
        quantities: QuantityFormatter,
        discreet: Boolean,
    ): Notification {
        val public = publicVersion(context)
        val open = open(AppOpenRequest.Product(notice.productId))
        // Con el modo discreto, también desbloqueado se ve solo el texto genérico.
        if (discreet) {
            return builder(context.getString(R.string.notif_stock_public))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .build()
        }
        val title =
            if (notice.onHand > QuantityMilli.ZERO) {
                context.getString(R.string.notif_stock_low_title, notice.productName)
            } else {
                context.getString(R.string.notif_stock_out_title, notice.productName)
            }
        val body =
            context.getString(
                R.string.notif_stock_low_body,
                quantityText(notice.onHand, notice.unit, quantities, context),
                quantityText(notice.minStock, notice.unit, quantities, context),
            )
        return builder(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .build()
    }

    /** El resumen de varios avisos: solo dice cuántos, así que se ve con el teléfono bloqueado. */
    private fun summary(count: Int, context: Context): Notification =
        builder(context.resources.getQuantityString(R.plurals.notif_stock_many_title, count, count))
            .setContentText(context.getString(R.string.notif_stock_many_body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroupSummary(true)
            .setContentIntent(open(AppOpenRequest.LowStock))
            .build()

    /** Lo único que se ve con el teléfono bloqueado. Al tocarla se abre el aviso entero. */
    private fun publicVersion(context: Context): Notification =
        builder(context.getString(R.string.notif_stock_public))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun builder(title: String): NotificationCompat.Builder = NotificationCompat
        .Builder(app, NotificationChannels.STOCK_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setGroup(GROUP)
        .setAutoCancel(true)

    private fun open(request: AppOpenRequest): PendingIntent {
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

    /** Si los avisos de la app y el canal de stock están permitidos. */
    private fun canPost(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(NotificationChannels.STOCK_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // El permiso se comprueba antes, con areNotificationsEnabled, y su rechazo se atrapa al llamar.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, id: Int, notification: Notification) {
        manager.notify(TAG, id, notification)
    }

    internal companion object {
        /**
         * Una cantidad con su unidad, como en las pantallas: con su símbolo o, sin símbolo, su
         * nombre. Una unidad de la persona no tiene plural conocido, así que va como `3 × caja`.
         * La usan también los avisos de vencimiento.
         */
        fun quantityText(
            quantity: QuantityMilli,
            unit: MeasureUnit?,
            quantities: QuantityFormatter,
            context: Context,
        ): String {
            if (unit == null) return quantities.format(quantity, allowsFraction = true)
            val number = quantities.format(quantity, unit.allowsFraction)
            val resources = context.resources
            val label =
                SeedText.unitSymbol(resources, unit.systemKey, unit.symbol)
                    ?: SeedText.displayName(resources, SeedTable.UNITS, unit.systemKey, unit.name)
            return if (unit.systemKey == null) {
                context.getString(R.string.format_custom_unit_quantity, number, label)
            } else {
                number + IcuTextNormalizer.NO_BREAK_SPACE + label
            }
        }

        /** La etiqueta de los avisos de stock, para no chocar con los del respaldo. */
        private const val TAG = "low_stock"

        /** El grupo de los avisos de stock. */
        private const val GROUP = "low_stock"

        /** El número fijo del resumen. Ningún producto tiene el id 0. */
        private const val SUMMARY_ID = 0
    }
}
