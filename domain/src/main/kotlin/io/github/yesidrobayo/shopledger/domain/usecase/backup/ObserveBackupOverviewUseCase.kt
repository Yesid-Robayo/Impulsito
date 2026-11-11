package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.StaleReason
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupStatusSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.backup.BackupStatusResolver
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * El estado del respaldo con sus hechos, para la pantalla de inicio y la del respaldo. Como
 * [ObserveBackupStatusUseCase], se calcula con el reloj del momento y no depende de ningún trabajo
 * en segundo plano. No escribe nada.
 */
class ObserveBackupOverviewUseCase(
    private val source: BackupStatusSource,
    private val clock: Clock,
) {
    /**
     * El estado, de nuevo con cada cambio guardado o de los trabajos de subida, y con cada emisión
     * de [refreshes]: el paso del tiempo solo no emite, y la pantalla vuelve a pedirlo al volver a
     * primer plano, para ver un día sin copia aunque nada haya cambiado.
     */
    operator fun invoke(refreshes: Flow<Unit>): Flow<BackupOverview> =
        combine(source.observe(), refreshes) { facts, _ -> overviewOf(facts, clock.now()) }
            .distinctUntilChanged { old, new -> old.copy(computedAt = new.computedAt) == new }

    private fun overviewOf(facts: BackupStatusFacts, now: Instant): BackupOverview {
        val report = BackupStatusResolver.resolve(facts, now)
        return BackupOverview(
            report = report,
            destinationKind = facts.destinationKind,
            lastSuccessAt = facts.lastSuccessAt,
            failureCode = facts.failureCode,
            staleReason = if (report.status ==
                BackupStatus.STALE
            ) {
                staleReason(facts, now)
            } else {
                null
            },
            meteredPolicy = facts.meteredPolicy,
            nextMeteredUploadAt =
                BackupTimings.meteredInterval(facts.meteredPolicy)?.let { interval ->
                    facts.lastUploadFinishedAt?.plus(interval)
                },
            lastFullReadAt = facts.lastFullVerifyAt ?: facts.connectedAt,
            computedAt = now,
        )
    }

    /**
     * El motivo que se puede afirmar de un día sin copia: el reloj atrasado primero, porque explica
     * todo lo demás; después la falta de Wi-Fi con copias solo por Wi-Fi, y la falta de red. Si nada
     * de eso pasa, no se inventa un motivo. Con la nube del teléfono la red la decide la otra app:
     * la app no sabe si le faltó.
     */
    private fun staleReason(facts: BackupStatusFacts, now: Instant): StaleReason? {
        val oldest = facts.firstUnsyncedAt
        val lastSuccess = facts.lastSuccessAt
        val network = facts.network
        val webDav = facts.destinationKind == BackupDestinationKind.WEBDAV
        return when {
            (oldest != null && now < oldest) || (lastSuccess != null && now < lastSuccess) ->
                StaleReason.CLOCK

            webDav && facts.meteredPolicy == MeteredBackupPolicy.WIFI_ONLY &&
                (!network.validated || network.metered) -> StaleReason.NO_WIFI

            webDav && !network.validated -> StaleReason.NETWORK

            else -> null
        }
    }
}
