package io.github.yesidrobayo.shopledger.notification

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.localization.LocalizedSurface

/**
 * Los canales de avisos de la app, que Android muestra en sus ajustes con su nombre y su
 * descripción.
 *
 * Android guarda el nombre y la descripción tal como llegaron al crear el canal, así que un cambio
 * de idioma no los alcanza solo. Por eso son una superficie con textos: al cambiar de idioma se
 * vuelven a crear **con el mismo ID**, y Android solo cambia su nombre y su descripción. El ID no
 * cambia nunca: uno nuevo sería otro canal, sin lo que la persona eligió en el anterior, como
 * silenciarlo. Por la misma razón, volver a crearlo no toca la importancia que la persona cambió.
 *
 * Crear un canal no necesita el permiso de avisos.
 *
 * @param channels los canales que crea. La app usa [APP_CHANNELS], y las pruebas, uno propio.
 */
class NotificationChannels(
    private val app: Context,
    private val channels: List<NotificationChannelSpec>,
) : LocalizedSurface {
    /**
     * Crea los canales con los textos de [texts], o les cambia el nombre y la descripción si ya
     * existen. Es una llamada al sistema: va fuera del hilo principal.
     */
    override suspend fun refresh(texts: Context) {
        if (channels.isEmpty()) return
        NotificationManagerCompat.from(app).createNotificationChannelsCompat(
            channels.map { spec ->
                NotificationChannelCompat
                    .Builder(spec.id, spec.importance.value)
                    .setName(texts.getString(spec.name))
                    .setDescription(spec.description?.let { texts.getString(it) })
                    .build()
            },
        )
    }

    companion object {
        /**
         * El canal del respaldo: un día sin copia y, después, lo que detiene las copias hasta que
         * la persona actúe. Nace con la importancia de siempre, y desde ahí la decide la persona.
         */
        const val BACKUP_CHANNEL_ID = "backup"

        /** El canal de los avisos de stock bajo: un producto que llegó a su mínimo. */
        const val STOCK_CHANNEL_ID = "low_stock"

        /** El canal de los avisos de vencimiento: un lote que entró en sus días de aviso. */
        const val EXPIRY_CHANNEL_ID = "expiry"

        /** El canal del resumen del día: sin sonido, porque es un aviso que la persona pidió. */
        const val DAILY_SUMMARY_CHANNEL_ID = "daily_summary"

        /** El canal de los recordatorios de gastos recurrentes que esperan confirmación. */
        const val RECURRING_CHANNEL_ID = "recurring"

        /** El canal del aviso del límite de ingresos que escribió la persona. */
        const val LIMITS_CHANNEL_ID = "limits"

        /** El canal del aviso de una versión nueva, de la búsqueda automática. */
        const val UPDATES_CHANNEL_ID = "updates"

        /**
         * Los canales de la app. Cada aviso suma aquí su canal cuando llega, con un ID que no cambia
         * nunca.
         */
        val APP_CHANNELS: List<NotificationChannelSpec> =
            listOf(
                NotificationChannelSpec(
                    id = STOCK_CHANNEL_ID,
                    name = R.string.notif_channel_stock,
                    description = null,
                    importance = ChannelImportance.DEFAULT,
                ),
                NotificationChannelSpec(
                    id = BACKUP_CHANNEL_ID,
                    name = R.string.notif_channel_backup,
                    description = null,
                    importance = ChannelImportance.DEFAULT,
                ),
                NotificationChannelSpec(
                    id = EXPIRY_CHANNEL_ID,
                    name = R.string.notif_channel_expiry,
                    description = null,
                    importance = ChannelImportance.DEFAULT,
                ),
                NotificationChannelSpec(
                    id = DAILY_SUMMARY_CHANNEL_ID,
                    name = R.string.notif_channel_daily_summary,
                    description = null,
                    importance = ChannelImportance.LOW,
                ),
                NotificationChannelSpec(
                    id = RECURRING_CHANNEL_ID,
                    name = R.string.notif_channel_recurring,
                    description = null,
                    importance = ChannelImportance.DEFAULT,
                ),
                NotificationChannelSpec(
                    id = LIMITS_CHANNEL_ID,
                    name = R.string.notif_channel_limits,
                    description = null,
                    importance = ChannelImportance.DEFAULT,
                ),
                // Sin sonido: una versión nueva nunca es urgente, y nada se bloquea sin ella.
                NotificationChannelSpec(
                    id = UPDATES_CHANNEL_ID,
                    name = R.string.notif_channel_updates,
                    description = null,
                    importance = ChannelImportance.LOW,
                ),
            )
    }
}

/**
 * Un canal de avisos.
 *
 * @property id su identificador, en inglés y para siempre: con otro sería otro canal.
 * @property name el recurso de su nombre, que la persona ve en los ajustes de Android.
 * @property description el recurso de su descripción, o `null` si no tiene.
 * @property importance la importancia con que nace. Después la decide la persona.
 */
data class NotificationChannelSpec(
    val id: String,
    @param:StringRes val name: Int,
    @param:StringRes val description: Int?,
    val importance: ChannelImportance,
)

/** Cuánto interrumpe un canal al nacer. */
enum class ChannelImportance(val value: Int) {
    /** Suena y aparece en la barra de estado. */
    DEFAULT(NotificationManagerCompat.IMPORTANCE_DEFAULT),

    /** Aparece en la barra de estado, sin sonido. */
    LOW(NotificationManagerCompat.IMPORTANCE_LOW),
}
