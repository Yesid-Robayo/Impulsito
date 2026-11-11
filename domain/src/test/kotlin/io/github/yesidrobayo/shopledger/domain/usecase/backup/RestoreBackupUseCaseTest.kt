package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.SafetyCopyInfo
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
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
            override suspend fun restoreCopy(copyKey: String): RestoreProgress {
                calls += "copy:$copyKey"
                return RestoreProgress.AskEmptyCopy
            }

            override suspend fun restoreFile(uri: String): RestoreProgress {
                calls += "file:$uri"
                return RestoreProgress.Refused(RestoreProblem.DAMAGED)
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
        assertEquals(listOf("copy:a", "file:content://b", "answer:Repair", "cancel"), calls)
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
        )
        asking.forEach { assertTrue("$it", RestoreBackupUseCase.asks(it)) }
        stopped.forEach { assertFalse("$it", RestoreBackupUseCase.asks(it)) }
    }
}
