package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Dictar directo con la voz del teléfono`, en Ajustes: apagado de inicio. Encenderlo pide antes
 * leer qué cambia, porque el servicio de voz es del teléfono y puede usar internet. Apagarlo es
 * inmediato. Si el teléfono no tiene un servicio de voz, la fila no aparece.
 *
 * La elección es de este teléfono: no viaja en las copias ni cambia al restaurar. No necesita la
 * base, así que se ve también sin ella.
 */
@Composable
fun VoiceInputSetting() {
    val context = LocalContext.current
    val app = context.applicationContext
    val container = (app as ShopLedgerApp).container
    val preference = container.voiceInput
    val available by produceState<Boolean?>(initialValue = null, container) {
        value = withContext(container.dispatchers.io) { VoiceInput.isAvailable(app) }
    }
    LaunchedEffect(preference) { preference.load() }
    val enabled by preference.enabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    VoiceInputRow(
        available = available == true,
        enabled = enabled,
        confirming = confirming,
        saveFailed = saveFailed,
        onToggle = { on ->
            if (on) {
                confirming = true
            } else {
                scope.launch { saveFailed = !preference.set(false) }
            }
        },
        onConfirm = {
            confirming = false
            scope.launch { saveFailed = !preference.set(true) }
        },
        onDismiss = { confirming = false },
    )
}

/**
 * La fila del ajuste, su aviso al encenderlo y su error al guardar. Nada se dibuja sin servicio de
 * voz ni mientras la elección no se leyó.
 */
@Composable
internal fun VoiceInputRow(
    available: Boolean,
    enabled: Boolean?,
    confirming: Boolean,
    saveFailed: Boolean,
    onToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!available || enabled == null) return
    SettingsRow(
        title = stringResource(R.string.voice_input_setting),
        summary = stringResource(R.string.voice_input_summary),
        action = SettingsRowAction.Toggle(enabled, onToggle),
    )
    if (saveFailed) {
        NoticeCard(
            text = stringResource(R.string.state_error_save),
            variant = NoticeVariant.ERROR,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
    }
    if (confirming) {
        val app = stringResource(R.string.app_name)
        ConfirmDialog(
            title = stringResource(R.string.voice_input_confirm_title),
            body = stringResource(R.string.voice_input_confirm_body, app),
            confirmLabel = stringResource(R.string.voice_input_confirm_action),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}
