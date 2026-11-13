package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeRequest
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.SafetyCopyInfo
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restaurar pasa cada pedido al puerto tal cual, y solo las preguntas esperan una respuesta. */
class RestoreBackupUseCaseTest {
    private val calls = mutableListOf<String>()
    private val port =
        object : BackupRestorePort {
            override suspend fun restoreCopy(copyKey: String, code: String?): RestoreProgress {
                calls += "copy:$copyKey" + (code?.let { ":con código" } ?: "")
                return RestoreProgress.AskEmptyCopy
            }

            override suspend fun restoreFile(uri: String, code: String?): RestoreProgress {
                calls += "file:$uri" + (code?.let { ":con código" } ?: "")
                return RestoreProgress.Refused(RestoreProblem.DAMAGED)
            }

            override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? {
                calls += "before-update-copy"
                return BeforeUpdateCopy(Instant.parse("2026-11-01T10:00:00Z"))
            }

            override suspend fun restoreBeforeUpdate(): RestoreProgress {
                calls += "before-update"
                return RestoreProgress.Refused(RestoreProblem.NEEDS_UPDATE)
            }

            override suspend fun answer(answer: RestoreAnswer): RestoreProgress {
                calls += "answer:$answer"
                return RestoreProgress.ReadyToRestart(1)
            }

            override suspend fun cancel(): Boolean {
                calls += "cancel"
                return true
            }
        }

    @Test
    fun `every request reaches the port as it is`() = runTest {
        val use = RestoreBackupUseCase(port)

        assertEquals(RestoreProgress.AskEmptyCopy, use.copy("a"))
        assertEquals(RestoreProgress.Refused(RestoreProblem.DAMAGED), use.file("content://b"))
        assertEquals(RestoreProgress.ReadyToRestart(1), use.answer(RestoreAnswer.Repair))
        assertTrue(use.cancel())
        assertEquals(
            BeforeUpdateCopy(Instant.parse("2026-11-01T10:00:00Z")),
            use.beforeUpdateCopy(),
        )
        assertEquals(RestoreProgress.Refused(RestoreProblem.NEEDS_UPDATE), use.beforeUpdate())
        use.copy("a", "CODIGO-INVENTADO")
        use.file("content://b", "CODIGO-INVENTADO")
        assertEquals(
            listOf(
                "copy:a",
                "file:content://b",
                "answer:Repair",
                "cancel",
                "before-update-copy",
                "before-update",
                "copy:a:con código",
                "file:content://b:con código",
            ),
            calls,
        )
    }

    @Test
    fun `only questions wait for an answer`() {
        val asking = listOf(
            RestoreProgress.AskRepair(2),
            RestoreProgress.AskEmptyCopy,
            RestoreProgress.AskWithoutSafetyCopy,
            RestoreProgress.AskDiscardSafetyCopy(listOf(SafetyCopyInfo(null))),
        )
        val stopped = listOf(
            RestoreProgress.Busy,
            RestoreProgress.NotEnoughSpace(1),
            RestoreProgress.Refused(RestoreProblem.OTHER),
            RestoreProgress.Failed,
            RestoreProgress.ReadyToRestart(0),
            // Pedir el código no deja nada en espera: con él, la restauración empieza de nuevo.
            RestoreProgress.AskRecoveryCode(RecoveryCodeRequest.NEEDED),
        )
        asking.forEach { assertTrue("$it", RestoreBackupUseCase.asks(it)) }
        stopped.forEach { assertFalse("$it", RestoreBackupUseCase.asks(it)) }
    }
}
