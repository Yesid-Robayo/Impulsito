package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** El caso de uso del cifrado pasa cada pedido a su puerto, sin cambiar nada. */
class EncryptionUseCaseTest {
    private val draft =
        RecoveryCodeDraft(listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51"))
    private val calls = mutableListOf<String>()
    private val port =
        object : EncryptionPort {
            override suspend fun status(): EncryptionStatus = EncryptionStatus.On(5L).also {
                calls +=
                    "status"
            }

            override fun newCode(): RecoveryCodeDraft = draft.also { calls += "new" }

            override fun checkTyped(draft: RecoveryCodeDraft, typed: String): TypedCodeCheck =
                TypedCodeCheck.GroupMismatch(2).also { calls += "check:$typed" }

            override suspend fun roundTrip(draft: RecoveryCodeDraft): Boolean = false.also {
                calls +=
                    "trip"
            }

            override suspend fun activate(draft: RecoveryCodeDraft): ActivationOutcome =
                ActivationOutcome.SAVE_FAILED.also { calls += "activate" }
        }
    private val useCase = EncryptionUseCase(port)

    @Test
    fun `every call reaches the port`() = runTest {
        assertEquals(EncryptionStatus.On(5L), useCase.status())
        assertSame(draft, useCase.newCode())
        assertEquals(TypedCodeCheck.GroupMismatch(2), useCase.checkTyped(draft, "X"))
        assertEquals(false, useCase.roundTrip(draft))
        assertEquals(ActivationOutcome.SAVE_FAILED, useCase.activate(draft))
        assertEquals(listOf("status", "new", "check:X", "trip", "activate"), calls)
    }
}
