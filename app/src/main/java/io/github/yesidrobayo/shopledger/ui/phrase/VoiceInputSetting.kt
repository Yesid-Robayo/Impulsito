package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Escuchar al tocar Decir`, en Ajustes: encendido de inicio. Encendido, `Decir` muestra la
 * pantalla de escuchar; apagado, abre la hoja para escribir o dictar con el teclado. Cambiarlo es
 * inmediato en los dos sentidos: lo que hay que explicar, el micrófono, se explica la primera vez
 * que se escucha. Si el teléfono no tiene un servicio de voz, la fila no aparece.
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
        value = withContext(container.dispatchers.io) { PhoneSpeechEngine.isAvailable(app) }
    }
    LaunchedEffect(preference) { preference.load() }
    val enabled by preference.enabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saveFailed by remember { mutableStateOf(false) }
    VoiceInputRow(
        available = available == true,
        enabled = enabled,
        saveFailed = saveFailed,
        onToggle = { on -> scope.launch { saveFailed = !preference.set(on) } },
    )
}

/**
 * La fila del ajuste y su error al guardar. Nada se dibuja sin servicio de voz ni mientras la
 * elección no se leyó.
 */
@Composable
internal fun VoiceInputRow(
    available: Boolean,
    enabled: Boolean?,
    saveFailed: Boolean,
    onToggle: (Boolean) -> Unit,
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
}

@ThemePreviews
@Composable
private fun VoiceInputRowPreview() {
    PreviewTheme {
        VoiceInputRow(available = true, enabled = true, saveFailed = true, onToggle = {})
    }
}
