package io.github.yesidrobayo.shopledger.domain.rule.surface

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.surface.AmountDecision
import io.github.yesidrobayo.shopledger.domain.model.surface.DeviceLockState
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetContent
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetFacts

/**
 * La regla única para mostrar un monto fuera de la app. La pantalla de inicio y el panel rápido se
 * ven sin autenticar: el monto solo aparece si la persona lo pidió, si el teléfono está abierto y
 * si el modo discreto está apagado.
 */
object AmountVisibilityPolicy {
    fun decide(
        chosenByOwner: Boolean,
        lock: DeviceLockState,
        discreetMode: Boolean,
    ): AmountDecision = when {
        !chosenByOwner -> AmountDecision.HIDDEN_BY_DEFAULT
        !lock.isOpenToOwner -> AmountDecision.HIDDEN_LOCKED
        discreetMode -> AmountDecision.HIDDEN_DISCREET
        else -> AmountDecision.VISIBLE
    }
}

/** Qué dibuja el widget de venta rápida con lo que leyó de la base. */
object QuickSaleWidgetRules {
    /**
     * El contenido del widget.
     *
     * @param facts lo leído de la base, o `null` si la base no está lista.
     * @param amountsChosen si la persona pidió montos en este widget.
     * @param lock cómo está el teléfono al dibujar.
     * @param discreetMode si el modo discreto está encendido.
     */
    fun contentOf(
        facts: WidgetFacts?,
        amountsChosen: Boolean,
        lock: DeviceLockState,
        discreetMode: Boolean,
    ): WidgetContent {
        if (facts == null) return WidgetContent.Neutral
        if (!facts.hasProducts) return WidgetContent.NoProducts
        val salesCount = facts.salesCount ?: return WidgetContent.Neutral
        val decision = AmountVisibilityPolicy.decide(amountsChosen, lock, discreetMode)
        return WidgetContent.Summary(
            salesCount = salesCount,
            lowStockCount = facts.lowStockCount,
            amountsChosen = amountsChosen,
            amount = if (decision.isVisible) facts.netSales else null,
            backupFailed = facts.backupFailed,
        )
    }

    /**
     * Si el estado del respaldo se dice en el widget como `No se pudo respaldar`: un fallo que no se
     * resuelve solo, el destino que ya no deja entrar o un conflicto. Esperar la red, subir o tener
     * cambios pendientes no lo es.
     */
    fun backupFailed(status: BackupStatus): Boolean = when (status) {
        BackupStatus.FAILED, BackupStatus.PERMISSION_LOST, BackupStatus.CONFLICT -> true

        BackupStatus.RESTORING,
        BackupStatus.NOT_CONNECTED,
        BackupStatus.POLICY_PENDING,
        BackupStatus.NOTHING_TO_BACK_UP,
        BackupStatus.UPLOADING,
        BackupStatus.STALE,
        BackupStatus.CHECK_DUE,
        BackupStatus.HANDED_OFF,
        BackupStatus.UP_TO_DATE,
        BackupStatus.BLOCKED_BY_DATA_SAVER,
        BackupStatus.WAITING_FOR_WIFI,
        BackupStatus.WAITING_FOR_NETWORK,
        BackupStatus.PENDING,
        -> false
    }
}
