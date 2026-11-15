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
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.port.RevenueLimitNotifier
import io.github.yesidrobayo.shopledger.domain.rule.report.RevenueLimitRules
import io.github.yesidrobayo.shopledger.localization.LocalizedContextProvider
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf

/**
 * El aviso del límite de ingresos, en el canal de metas y límites y en el idioma de la interfaz.
 *
 * - **Sin montos, nunca.** Dice a qué parte del límite se llegó, el 80 % o el 95 %, o que se pasó.
 *   Con el teléfono bloqueado solo dice que hay un aviso, y con el modo discreto la versión
 *   privada tampoco dice más.
 * - **Es una orientación.** El límite lo escribió la persona: el texto no nombra ninguna norma.
 * - **Tocarlo abre `Metas y límites`**, con un aviso explícito e inmutable que no lleva datos.
 * - **Dice si salió.** Sin permiso, con los avisos de la app apagados o con el canal silenciado,
 *   no sale y responde `false`, sin fallar.
 *
 * @param settings lee los ajustes vigentes: el formato del porcentaje y el modo discreto.
 */
class AndroidRevenueLimitNotifier(
    private val app: Context,
    private val texts: LocalizedContextProvider,
    private val settings: suspend () -> AppSettings,
) : RevenueLimitNotifier {
    override suspend fun show(level: LimitLevel, used: BasisPoints?): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (level == LimitLevel.BELOW || !canPost(manager)) return false
        val current = settings()
        val context = texts.context()
        val publicVersion = publicVersion(context)
        val notification =
            if (current.preferences.discreetModeEnabled) {
                publicVersion
            } else {
                val formats =
                    businessFormatsOf(current.regional, context.resources.configuration.locales[0])
                val body = when (level) {
                    LimitLevel.EXCEEDED -> context.getString(R.string.notif_limit_body_over)

                    LimitLevel.SECOND_NOTICE -> context.getString(
                        R.string.notif_limit_body_reached,
                        formats.percent.format(RevenueLimitRules.SECOND_NOTICE),
                    )

                    LimitLevel.FIRST_NOTICE, LimitLevel.BELOW -> context.getString(
                        R.string.notif_limit_body_reached,
                        formats.percent.format(RevenueLimitRules.FIRST_NOTICE),
                    )
                }
                builder(context.getString(R.string.notif_limit_title))
                    .setContentText(body)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion)
                    .setContentIntent(open())
                    .build()
            }
        return try {
            post(manager, notification)
            true
        } catch (_: SecurityException) {
            // Desde Android 13, sin el permiso de avisos el sistema lo rechaza.
            false
        }
    }

    /** Lo único que se ve con el teléfono bloqueado. Al tocarla se abre la pantalla. */
    private fun publicVersion(context: Context): Notification = builder(
        context.getString(R.string.notif_limit_public, context.getString(R.string.app_name)),
    )
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(open())
        .build()

    private fun builder(title: String): NotificationCompat.Builder = NotificationCompat
        .Builder(app, NotificationChannels.LIMITS_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)

    private fun open(): PendingIntent {
        val request = AppOpenRequest.GoalsAndLimits
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

    /** Si los avisos de la app y el canal de metas y límites están permitidos. */
    private fun canPost(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(NotificationChannels.LIMITS_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // El permiso se comprueba antes, con areNotificationsEnabled, y su rechazo se atrapa al
    // llamar.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, notification: Notification) {
        manager.notify(TAG, ID, notification)
    }

    private companion object {
        /** La etiqueta del aviso del límite, para no chocar con los demás. */
        const val TAG = "revenue_limit"

        /** Un solo aviso a la vez: el nuevo reemplaza al anterior. */
        const val ID = 1
    }
}
