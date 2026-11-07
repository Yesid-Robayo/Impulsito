package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.port.BackupStatusSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.backup.AppOpenUploadRule
import io.github.yesidrobayo.shopledger.domain.rule.backup.BackupStatusResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El estado del respaldo para la pantalla de inicio y la del respaldo. Se calcula aquí, con el
 * reloj del momento, cada vez que alguien lo pide: no depende de que haya corrido ningún trabajo en
 * segundo plano, así que un teléfono que los mata igual muestra un día sin copia al abrir la app.
 *
 * No escribe nada.
 */
class ObserveBackupStatusUseCase(
    private val source: BackupStatusSource,
    private val clock: Clock,
) {
    /**
     * El estado, de nuevo cada vez que cambia el estado guardado. El paso del tiempo solo no emite:
     * la pantalla lo vuelve a pedir al volver a primer plano.
     */
    operator fun invoke(): Flow<BackupStatusReport> = source.observe()
        .map { BackupStatusResolver.resolve(it, clock.now()) }
        .distinctUntilChanged()

    /** El estado de ahora. */
    suspend fun current(): BackupStatusReport =
        BackupStatusResolver.resolve(source.current(), clock.now())

    /** Lo que hace el respaldo al abrir la app, con el estado de ahora. */
    suspend fun onAppOpen(): AppOpenUpload = AppOpenUploadRule.decide(current())
}
