package io.github.yesidrobayo.shopledger.ui.permission

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
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
 * El permiso de avisos, pedido en contexto: nunca al abrir la app, sino cuando se entiende para qué
 * sirve. Primero una hoja explica sus dos usos, el stock y las copias, y solo si la persona sigue
 * se abre el diálogo del sistema. Cerrar la hoja o negar el permiso no cambia nada más: la lista de
 * lo que se está acabando y el estado del respaldo se ven igual en la app, y no se insiste.
 *
 * Hoy se ofrece al guardar el primer producto. Las otras entradas, terminar una restauración y
 * encender los avisos de stock, llaman a [offer] cuando existan.
 *
 * @param needsAsking si hace falta pedirlo: Android 13 o superior y el permiso sin dar.
 * @param isFirstProduct si el producto recién guardado es el único del catálogo.
 * @param scope donde corren las lecturas que deciden si ofrecerlo.
 */
class NotificationPermissionPrompt(
    private val needsAsking: () -> Boolean,
    private val isFirstProduct: suspend () -> Boolean,
    private val scope: CoroutineScope,
) {
    /** Si la hoja que explica el permiso está a la vista. */
    var offered: Boolean by mutableStateOf(false)
        private set

    /** Ofrece el permiso si hace falta pedirlo. */
    fun offer() {
        if (needsAsking()) offered = true
    }

    /** Se guardó un producto nuevo: si es el primero, ofrece el permiso. */
    fun offerAfterNewProduct() {
        if (!needsAsking()) return
        scope.launch { if (isFirstProduct()) offer() }
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

/** El permiso de avisos de la navegación, o `null` fuera de ella, como en las vistas previas. */
val LocalNotificationPermission = staticCompositionLocalOf<NotificationPermissionPrompt?> { null }

/**
 * La hoja que explica el permiso y, al seguir, el diálogo del sistema. Lo que responda la persona
 * no se guarda aparte: el sistema ya lo recuerda.
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
