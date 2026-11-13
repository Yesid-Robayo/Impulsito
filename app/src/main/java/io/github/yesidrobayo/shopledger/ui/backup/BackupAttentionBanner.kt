package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusBanner
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** A dónde lleva la acción del banner del respaldo. */
enum class BackupBannerAction {
    /** La pantalla del respaldo, donde se revisa y se resuelve. */
    OPEN_BACKUP,

    /** Sin destino: elegir dónde guardar las copias. */
    CHOOSE_DESTINATION,

    /** Copias en pausa: revisar la política que cambió. */
    REVIEW_POLICY,

    /** `Entendido` en el aviso sin destino de Inicio: se reduce a una línea. */
    ACKNOWLEDGE,
}

/**
 * El banner del respaldo bajo la barra de una raíz: solo si el estado pide atención, con su frase y
 * su acción. Las esperas normales y lo que está bien no lo muestran, también `Todo guardado`. Es el
 * mismo en Inicio y en las demás raíces, y nunca va en la venta, en un detalle ni en un formulario.
 *
 * Sin destino es distinto: el aviso solo va en Inicio, con [home], donde lleva además
 * `Entendido`. En las demás raíces no aparece, porque la persona ya lo ve en Inicio y el chip lo
 * sigue diciendo; repetirlo en cada pestaña lo volvía ruido.
 */
@Composable
fun BackupAttentionBanner(
    backup: BackupWording,
    onAction: (BackupBannerAction) -> Unit,
    modifier: Modifier = Modifier,
    home: Boolean = false,
) {
    val status = backup.overview.report.status
    if (!status.needsBanner()) return
    if (status == BackupStatus.NOT_CONNECTED && !home) return
    val (label, action) =
        when (status) {
            BackupStatus.NOT_CONNECTED ->
                R.string.backup_action_choose_destination to BackupBannerAction.CHOOSE_DESTINATION

            BackupStatus.POLICY_PENDING ->
                R.string.backup_action_review_policy to BackupBannerAction.REVIEW_POLICY

            // Los demás que piden atención se revisan en la pantalla del respaldo, y los que no
            // llevan banner nunca llegan aquí: se nombran igual, para que uno nuevo se decida.
            BackupStatus.CHECK_DUE,
            BackupStatus.BLOCKED_BY_DATA_SAVER,
            BackupStatus.STALE,
            BackupStatus.FAILED,
            BackupStatus.PERMISSION_LOST,
            BackupStatus.CONFLICT,
            BackupStatus.RESTORING,
            BackupStatus.NOTHING_TO_BACK_UP,
            BackupStatus.UPLOADING,
            BackupStatus.HANDED_OFF,
            BackupStatus.UP_TO_DATE,
            BackupStatus.WAITING_FOR_WIFI,
            BackupStatus.WAITING_FOR_NETWORK,
            BackupStatus.PENDING,
            -> R.string.backup_action_review to BackupBannerAction.OPEN_BACKUP
        }
    val understood = home && status == BackupStatus.NOT_CONNECTED
    BackupStatusBanner(
        backupBannerLook(backup),
        modifier = modifier,
        actionLabel = stringResource(label),
        onAction = { onAction(action) },
        secondaryLabel = if (understood) {
            stringResource(
                R.string.backup_action_understood,
            )
        } else {
            null
        },
        onSecondary = { onAction(BackupBannerAction.ACKNOWLEDGE) },
    )
}

/**
 * Las palabras del estado del respaldo a partir de su resumen: cada resumen con la zona del negocio
 * y, con la nube del teléfono, el nombre de esa nube. El nombre se lee del disco con
 * [providerLabel], y de nuevo solo si cambia el tipo de destino.
 *
 * Lo comparten Inicio y las raíces: el estado y sus palabras se arman igual en todas.
 */
class BackupWordings(
    private val providerLabel: suspend () -> String?,
    private val computation: CoroutineContext,
) {
    private var providerFor: Pair<BackupDestinationKind?, String?>? = null

    fun of(overviews: Flow<BackupOverview>, zones: Flow<BusinessTimeZone?>): Flow<BackupWording> =
        combine(overviews, zones) { overview, zone -> overview to zone }
            .mapLatest { (overview, zone) ->
                BackupWording(overview, providerOf(overview.destinationKind), zone)
            }

    private suspend fun providerOf(kind: BackupDestinationKind?): String? {
        if (kind != BackupDestinationKind.DOCUMENT_SLOTS) return null
        providerFor?.takeIf { it.first == kind }?.let { return it.second }
        val label = withContext(computation) { providerLabel() }
        providerFor = kind to label
        return label
    }
}

/**
 * El estado del respaldo para el banner de una raíz. Si no se puede leer, no hay banner: la raíz
 * sigue igual. Se vuelve a leer cuando la pantalla vuelve a primer plano, para ver un día sin
 * copia aunque nada haya cambiado.
 *
 * @param backupOverview el resumen del respaldo; recibe las veces que la pantalla vuelve.
 */
class BackupBannerViewModel(
    backupOverview: (Flow<Unit>) -> Flow<BackupOverview>,
    providerLabel: suspend () -> String?,
    zones: Flow<BusinessTimeZone?>,
    computation: CoroutineContext,
) : ViewModel() {
    private val returns = MutableStateFlow(0L)

    val backup: StateFlow<BackupWording?> =
        BackupWordings(providerLabel, computation)
            .of(backupOverview(returns.map {}), zones)
            .map<BackupWording, BackupWording?> { it }
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /** La pantalla volvió a primer plano: el estado se lee de nuevo. */
    fun onReturnToForeground() {
        returns.value += 1
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}

/**
 * El banner del respaldo de una raíz, con su propio estado: lo lee de la base, lo muestra solo si
 * pide atención y resuelve su acción. Sin base no dibuja nada.
 *
 * @param onOpen abre una pantalla: la del respaldo o la de elegir dónde guardar.
 */
@Composable
fun RootBackupBanner(onOpen: (AppNavKey) -> Unit, modifier: Modifier = Modifier) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val container = dependencies.container
            BackupBannerViewModel(
                backupOverview = { refreshes ->
                    ObserveBackupOverviewUseCase(ports.backupStatus, container.clock)(refreshes)
                },
                providerLabel = {
                    val reader = BackupDestinationReader(container.data.backupDestination)
                    (reader.describe() as? BackupDestinationInfo.Slots)?.providerLabel
                },
                zones = BusinessCalendarSource(ports.settings).observe().map {
                    (it as? BusinessCalendarResult.Ready)?.calendar?.zone
                },
                computation = container.dispatchers.io,
            )
        }
    val viewModel = (model as? ScreenModel.Ready)?.viewModel ?: return
    LifecycleResumeEffect(viewModel) {
        viewModel.onReturnToForeground()
        onPauseOrDispose {}
    }
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val policy = LocalPolicyPrompt.current
    backup?.let { wording ->
        BackupAttentionBanner(
            wording,
            onAction = { action ->
                when (action) {
                    BackupBannerAction.OPEN_BACKUP -> onOpen(AppNavKey.Backup)

                    BackupBannerAction.CHOOSE_DESTINATION ->
                        onOpen(AppNavKey.BackupDestinationChoice())

                    BackupBannerAction.REVIEW_POLICY -> policy.review()

                    // Solo Inicio ofrece `Entendido`: aquí no llega.
                    BackupBannerAction.ACKNOWLEDGE -> Unit
                }
            },
            modifier = modifier.rootBannerSpacing(),
        )
    }
}

/** El margen del banner bajo la barra de una raíz: el mismo que el resto del contenido. */
@Composable
fun Modifier.rootBannerSpacing(): Modifier =
    padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
