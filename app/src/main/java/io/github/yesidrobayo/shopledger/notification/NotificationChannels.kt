package io.github.yesidrobayo.shopledger.notification

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
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
         * Los canales de la app. Todavía no hay avisos: cada uno suma aquí su canal cuando llega,
         * con un ID que no cambia nunca.
         */
        val APP_CHANNELS: List<NotificationChannelSpec> = emptyList()
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
