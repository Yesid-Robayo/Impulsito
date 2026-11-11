package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.domain.port.HumanCheckRecorder
import kotlin.time.Instant

/**
 * Anota la revisión humana en `backup_state`, fuera de una transacción del negocio: no es un cambio
 * que deba respaldarse, y no suma a `change_seq`. El estado del respaldo la ve enseguida, porque
 * observa esa fila.
 */
internal class RoomHumanCheckRecorder(private val state: BackupStateDao) : HumanCheckRecorder {
    override suspend fun recordSeen(generation: Long, at: Instant): Boolean =
        state.recordHumanCheck(at.toEpochMilliseconds(), generation) == 1
}
