package io.github.yesidrobayo.shopledger.ui.permission

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.notification.NotificationChannels
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * El permiso de avisos, con una sola forma de pedirlo: primero una hoja corta explica para qué
 * sirve, los avisos de copias, los recordatorios y las versiones nuevas, y solo si la persona sigue
 * se abre el diálogo del sistema. Solo desde Android 13: antes no hay permiso que pedir.
 *
 * - **Sola, una vez en la vida:** [offerOnce] lo ofrece al terminar la configuración inicial o, a
 *   quien ya usaba la app y nunca respondió, la primera vez que la abre con esta versión. Queda
 *   anotado que se preguntó, aunque la persona cierre la hoja, y nunca se vuelve a ofrecer solo.
 *   Si ya lo negó antes, el sistema lo recuerda y tampoco se ofrece.
 * - **A pedido:** [offer], al encender los avisos de stock en `Ajustes`. Con el permiso negado,
 *   `Ajustes` dice que los avisos están apagados y abre los ajustes de avisos del sistema.
 *
 * Cerrar la hoja o negar el permiso no cambia nada más: la lista de lo que se está acabando, el
 * estado del respaldo y la versión nueva se ven igual en la app.
 *
 * @param needsAsking si hace falta pedirlo: Android 13 o superior y el permiso sin dar.
 * @param answeredBefore si la persona ya lo negó una vez: el sistema dice que conviene explicar.
 * @param wasAsked si la app ya lo ofreció sola en este teléfono.
 * @param markAsked anota que la app lo ofreció sola.
 * @param scope donde corren las lecturas que deciden si ofrecerlo.
 */
class NotificationPermissionPrompt(
    private val needsAsking: () -> Boolean,
    private val answeredBefore: () -> Boolean = { false },
    private val wasAsked: suspend () -> Boolean = { true },
    private val markAsked: suspend () -> Boolean = { true },
    private val scope: CoroutineScope,
) {
    /** Si la hoja que explica el permiso está a la vista. */
    var offered: Boolean by mutableStateOf(false)
        private set

    /** Si [offerOnce] ya decidió en este proceso: no vuelve a leer el disco. */
    private var decided = false

    /** Ofrece el permiso si hace falta pedirlo, porque la persona lo pidió. */
    fun offer() {
        if (needsAsking()) offered = true
    }

    /**
     * La app terminó la configuración inicial, o se abrió ya configurada: si nunca se ofreció
     * solo y hace falta, lo ofrece una vez y lo anota.
     */
    fun offerOnce() {
        if (decided) return
        decided = true
        scope.launch {
            if (wasAsked()) return@launch
            // Se anota siempre: con el permiso dado, sin permiso que pedir o ya negado, tampoco
            // hay que preguntar nunca más.
            markAsked()
            if (needsAsking() && !answeredBefore()) offered = true
        }
    }

    /** La persona cerró la hoja, o el sistema ya respondió. */
    fun dismiss() {
        offered = false
    }

    companion object {
        /**
         * Si hace falta pedir el permiso en este teléfono: solo desde Android 13, y solo si no está
         * dado. Antes de Android 13 el permiso no se pide en ejecución.
         */
        fun needsAsking(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

        /**
         * Si la persona ya negó el permiso una vez: Android dice que conviene explicarlo. Así se
         * sabe que ya respondió, aunque el permiso siga sin dar.
         */
        fun answeredBefore(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val activity = context.findActivity() ?: return false
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, POST_NOTIFICATIONS)
        }

        /** Si los avisos de la app están apagados en el teléfono, sin el permiso o a mano. */
        fun appNotificationsBlocked(context: Context): Boolean =
            !NotificationManagerCompat.from(context).areNotificationsEnabled()

        /**
         * Si los avisos de stock no pueden salir: sin el permiso, con los avisos de la app apagados
         * en el sistema o con el canal de stock silenciado.
         */
        fun stockAlertsBlocked(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return true
            val channel =
                manager.getNotificationChannelCompat(NotificationChannels.STOCK_CHANNEL_ID)
            return channel?.importance == NotificationManagerCompat.IMPORTANCE_NONE
        }

        /** Los ajustes de avisos de la app en el sistema. Solo los abre un toque de la persona. */
        fun openSettings(context: Context) {
            val intent =
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            context.startActivity(intent)
        }
    }
}

/** La actividad detrás de [this], o nula fuera de una, como en un trabajo. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** El permiso de avisos de la navegación, o `null` fuera de ella, como en las vistas previas. */
val LocalNotificationPermission = staticCompositionLocalOf<NotificationPermissionPrompt?> { null }

/**
 * La hoja que explica el permiso y, al seguir, el diálogo del sistema. Lo que responda la persona
 * no se guarda aparte: el sistema ya lo recuerda. Solo se anota que la app lo ofreció sola.
 */
@Composable
fun NotificationPermissionHost(prompt: NotificationPermissionPrompt) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            prompt.dismiss()
        }
    if (!prompt.offered) return
    AppBottomSheet(
        onDismissRequest = prompt::dismiss,
        title = null,
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.permission_notifications_action),
                onClick = {
                    prompt.dismiss()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(POST_NOTIFICATIONS)
                    }
                },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.permission_notifications_rationale),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
