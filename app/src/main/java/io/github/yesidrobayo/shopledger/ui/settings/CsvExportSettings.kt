package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportFileName
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.lock.rejectObscuredTouches
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.readOutcome
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Exportar a CSV`, en el grupo `Datos del negocio` de Ajustes.
 *
 * @param progress lo que lleva la exportación en curso, o `null` sin ninguna: con una en curso la fila
 *   no atiende toques y muestra su avance.
 * @param message lo que hay que avisar una sola vez: cómo terminó, o un toque rechazado.
 */
@Immutable
data class CsvExportUiState(
    val progress: CsvExportProgress? = null,
    val message: CsvExportMessage? = null,
) {
    /** Si hay una exportación en curso. */
    val working: Boolean get() = progress != null
}

/** Lo que se avisa al terminar una exportación, o al rechazar un toque. */
enum class CsvExportMessage {
    /** El archivo quedó guardado y comprobado. */
    SAVED,

    /** No hubo espacio donde se guardaba. */
    NO_SPACE,

    /** Otra falla: los datos no cambiaron, y se puede reintentar. */
    FAILED,

    /** Otra app dibujada encima tapaba la fila: el toque no cuenta. */
    OBSCURED_TOUCH,
}

/** Lo que la persona hace en el grupo `Datos del negocio`. */
sealed interface CsvExportAction {
    /**
     * Toca `Exportar a CSV`. La navegación pide antes la verificación del bloqueo y abre el selector
     * para crear el archivo.
     */
    data object Export : CsvExportAction

    /** El selector creó el documento: la exportación se escribe ahí. */
    data class DocumentCreated(val uri: String) : CsvExportAction

    /** El toque llegó a través de otra app dibujada encima. */
    data object ObscuredTouch : CsvExportAction

    /** El aviso ya se mostró. */
    data object MessageShown : CsvExportAction
}

/**
 * `Exportar a CSV` en Ajustes: exporta los datos del negocio al documento que la persona creó con el
 * selector del sistema.
 *
 * Exportar solo lee la base: mientras tanto se puede seguir vendiendo. Una exportación a la vez: la
 * fila no atiende toques mientras trabaja, y un documento nuevo que llegue entonces se ignora. Una
 * falla nunca cierra la pantalla, y sale como aviso con `Reintentar`.
 *
 * @param export escribe la exportación en el documento, avisando el avance.
 * @param zone la zona del negocio, para la hora del nombre sugerido.
 * @param now el instante actual.
 * @param appName el nombre de la app, que encabeza el nombre sugerido.
 * @param computation el hilo donde se exporta, fuera del principal.
 */
class CsvExportViewModel(
    private val export: suspend (String, (CsvExportProgress) -> Unit) -> CsvExport,
    private val zone: suspend () -> BusinessTimeZone?,
    private val now: () -> Instant,
    private val appName: String,
    private val computation: CoroutineContext,
) : ViewModel() {
    private val state = MutableStateFlow(CsvExportUiState())
    val uiState: StateFlow<CsvExportUiState> = state.asStateFlow()

    private var businessZone: BusinessTimeZone? = null

    init {
        // Sin zona, que solo falta antes de configurar la región o con una lectura fallida, el nombre
        // sugerido va en UTC: una falla aquí no es un error de la pantalla.
        viewModelScope.launch {
            val read = withContext(computation) { readOutcome { zone() } }
            businessZone = (read as? ReadOutcome.Read)?.value
        }
    }

    fun onAction(action: CsvExportAction) {
        when (action) {
            // La navegación abre la verificación y el selector; aquí solo se limpia el aviso viejo.
            CsvExportAction.Export -> state.update { it.copy(message = null) }

            is CsvExportAction.DocumentCreated -> exportTo(action.uri)

            CsvExportAction.ObscuredTouch ->
                state.update { it.copy(message = CsvExportMessage.OBSCURED_TOUCH) }

            CsvExportAction.MessageShown -> state.update { it.copy(message = null) }
        }
    }

    /** El nombre que el selector sugiere para el archivo. */
    fun suggestedName(): String = CsvExportFileName.of(appName, now(), businessZone)

    private fun exportTo(uri: String) {
        if (state.value.working) return
        state.update { it.copy(progress = CsvExportProgress(0, TOTAL_UNKNOWN), message = null) }
        viewModelScope.launch {
            val result =
                safely { export(uri) { progress -> state.update { it.copy(progress = progress) } } }
                    ?: CsvExport.Failed
            val message =
                when (result) {
                    is CsvExport.Saved -> CsvExportMessage.SAVED
                    CsvExport.NoSpace -> CsvExportMessage.NO_SPACE
                    CsvExport.Failed -> CsvExportMessage.FAILED
                }
            state.update { CsvExportUiState(progress = null, message = message) }
        }
    }

    /** Lo hecho, o nulo si falló: una falla nunca cierra la pantalla. */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Antes del primer archivo no se sabe cuántos hay: la barra arranca vacía. */
        const val TOTAL_UNKNOWN = 1
    }
}

/**
 * `Exportar a CSV`, en el grupo `Datos del negocio`: abre la verificación del bloqueo y el selector del
 * sistema. Mientras exporta, la fila dice `Exportando` con una barra de avance por archivo, y no atiende
 * toques. Los toques que llegan a través de otra app dibujada encima se rechazan: exportar saca todos
 * los datos del negocio.
 */
@Composable
fun CsvExportRow(state: CsvExportUiState, onAction: (CsvExportAction) -> Unit) {
    val title = stringResource(R.string.settings_export)
    val progress = state.progress
    if (progress == null) {
        SettingsRow(
            title = title,
            summary = stringResource(R.string.settings_export_summary),
            action = SettingsRowAction.Navigation { onAction(CsvExportAction.Export) },
            modifier = Modifier.rejectObscuredTouches { onAction(CsvExportAction.ObscuredTouch) },
        )
    } else {
        val fraction = progress.done.toFloat() / progress.total.coerceAtLeast(1)
        Column {
            SettingsRow(
                title = title,
                summary = stringResource(R.string.settings_export_working),
                action = SettingsRowAction.Value {},
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.s16),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun CsvExportRowPreview() {
    PreviewTheme {
        Column {
            SectionHeader(stringResource(R.string.settings_group_data))
            CsvExportRow(CsvExportUiState(), onAction = {})
            CsvExportRow(CsvExportUiState(progress = CsvExportProgress(4, 9)), onAction = {})
        }
    }
}
