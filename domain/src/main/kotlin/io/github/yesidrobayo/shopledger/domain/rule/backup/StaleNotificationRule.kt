package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import kotlin.time.Instant

/**
 * Decide si toca la notificación de un día sin copia. Sale solo con el estado
 * [BackupStatus.STALE], que ya excluye no tener destino: quien no conectó uno tiene su aviso
 * permanente en la pantalla de inicio, y no recibe una notificación cada día.
 *
 * Una a la vez como mucho cada [BackupTimings.STALE_NOTICE], contado desde la anterior. Con la hora
 * anterior a la última notificación, el reloj retrocedió: el plazo cuenta como vencido, porque un
 * reloj raro nunca esconde un aviso.
 */
object StaleNotificationRule {
    /** Si con el estado [status] en [now] sale la notificación, tras la anterior en [lastNoticeAt]. */
    fun isDue(status: BackupStatus, lastNoticeAt: Instant?, now: Instant): Boolean {
        if (status != BackupStatus.STALE) return false
        if (lastNoticeAt == null) return true
        return now < lastNoticeAt || now - lastNoticeAt >= BackupTimings.STALE_NOTICE
    }
}
