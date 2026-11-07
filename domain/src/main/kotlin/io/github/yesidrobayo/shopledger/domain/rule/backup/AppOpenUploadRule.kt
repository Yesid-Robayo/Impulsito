package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport

/**
 * Decide si abrir la app intenta subir en ese momento. Con la app a la vista no aplican los ahorros
 * de batería del sistema ni del fabricante, y un teléfono que mata los trabajos en segundo plano
 * recupera ahí la copia que no salió.
 *
 * Solo sube si hay cambios sin copia y nada lo impide: ni una restauración, ni un destino que falta,
 * espera a la persona o tiene un conflicto, ni la política sin aceptar, ni una base vacía, ni otra
 * subida corriendo. Un fallo anterior no lo impide: abrir la app es una de las salidas de una copia
 * que no se pudo restaurar, que no se repite sola.
 */
object AppOpenUploadRule {
    /** Lo que hace la apertura con el estado [report], calculado al abrir. */
    fun decide(report: BackupStatusReport): AppOpenUpload = when (report.status) {
        BackupStatus.RESTORING,
        BackupStatus.NOT_CONNECTED,
        BackupStatus.POLICY_PENDING,
        BackupStatus.PERMISSION_LOST,
        BackupStatus.CONFLICT,
        BackupStatus.NOTHING_TO_BACK_UP,
        BackupStatus.UPLOADING,
        -> AppOpenUpload.NONE

        // Un día sin copia ya es un aviso: no se espera además el intervalo.
        BackupStatus.STALE -> AppOpenUpload.PAST_INTERVAL

        BackupStatus.FAILED,
        BackupStatus.CHECK_DUE,
        BackupStatus.HANDED_OFF,
        BackupStatus.UP_TO_DATE,
        BackupStatus.BLOCKED_BY_DATA_SAVER,
        BackupStatus.WAITING_FOR_WIFI,
        BackupStatus.WAITING_FOR_NETWORK,
        BackupStatus.PENDING,
        ->
            if (report.pendingChanges > 0) AppOpenUpload.RESPECTING_INTERVAL else AppOpenUpload.NONE
    }
}
