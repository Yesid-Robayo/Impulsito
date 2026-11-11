package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileRejection
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupConfirm
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook
import io.github.yesidrobayo.shopledger.domain.port.SlotFilesSetupPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El caso de uso pasa a la capa de datos cada pedido tal cual, y devuelve lo que ella dice. */
class PrepareSlotFilesUseCaseTest {
    private val asked = ArrayList<Pair<BackupSlot, String>>()
    private val port =
        object : SlotFilesSetupPort {
            override suspend fun look(): SlotSetupLook? = null

            override suspend fun addFile(slot: BackupSlot, uri: String): SlotFileOutcome {
                asked += slot to uri
                return SlotFileOutcome.Rejected(SlotFileRejection.NOT_EMPTY)
            }

            override suspend fun confirm(): SlotSetupConfirm = SlotSetupConfirm.Incomplete
        }

    @Test
    fun `it passes each request through unchanged`() = runTest {
        val useCase = PrepareSlotFilesUseCase(port)

        assertNull(useCase.look())
        assertEquals(
            SlotFileOutcome.Rejected(SlotFileRejection.NOT_EMPTY),
            useCase.addFile(BackupSlot.B, "content://cloud/b"),
        )
        assertEquals(listOf(BackupSlot.B to "content://cloud/b"), asked)
        assertEquals(SlotSetupConfirm.Incomplete, useCase.confirm())
    }
}
