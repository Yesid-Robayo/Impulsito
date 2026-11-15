package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.CodeCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.DeactivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.backup.ResealOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * El caso de uso del cifrado pasa cada pedido a su puerto, sin cambiar nada, y anota en la
 * auditoría la activación y cada vez que se ve el código, con su resultado.
 */
class EncryptionUseCaseTest {
    private val draft =
        RecoveryCodeDraft(listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51"))
    private val calls = mutableListOf<String>()
    private val audits = mutableListOf<Pair<AuditEventType, AuditResult>>()
    private var activation = ActivationOutcome.SAVE_FAILED
    private var view: RecoveryCodeView = RecoveryCodeView.Unavailable
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
                activation.also { calls += "activate" }

            override suspend fun reseal(typed: String): ResealOutcome =
                ResealOutcome.OTHER_CODE.also { calls += "reseal:$typed" }

            override suspend fun viewCode(): RecoveryCodeView =
                view.also { calls += "view" }

            override suspend fun checkCode(typed: String): CodeCheckOutcome =
                CodeCheckOutcome.MISMATCH.also { calls += "test:$typed" }

            override suspend fun deactivate(): DeactivationOutcome =
                DeactivationOutcome.FAILED.also { calls += "off" }
        }
    private val useCase = EncryptionUseCase(port) { type, result -> audits += type to result }

    @Test
    fun `every call reaches the port`() = runTest {
        assertEquals(EncryptionStatus.On(5L), useCase.status())
        assertSame(draft, useCase.newCode())
        assertEquals(TypedCodeCheck.GroupMismatch(2), useCase.checkTyped(draft, "X"))
        assertEquals(false, useCase.roundTrip(draft))
        assertEquals(ActivationOutcome.SAVE_FAILED, useCase.activate(draft))
        assertEquals(ResealOutcome.OTHER_CODE, useCase.reseal("Y"))
        assertEquals(RecoveryCodeView.Unavailable, useCase.viewCode())
        assertEquals(CodeCheckOutcome.MISMATCH, useCase.checkCode("Z"))
        assertEquals(DeactivationOutcome.FAILED, useCase.deactivate())
        assertEquals(
            listOf(
                "status", "new", "check:X", "trip", "activate", "reseal:Y", "view", "test:Z", "off",
            ),
            calls,
        )
        // Lo que falló también queda, como fallo. Lo demás no se anota aquí.
        assertEquals(
            listOf(
                AuditEventType.ENCRYPTION_CHANGED to AuditResult.FAILURE,
                AuditEventType.RECOVERY_CODE_VIEWED to AuditResult.FAILURE,
            ),
            audits,
        )
    }

    @Test
    fun `an activation and a shown code are recorded as successes`() = runTest {
        activation = ActivationOutcome.ACTIVATED
        view = RecoveryCodeView.Shown(draft)

        assertEquals(ActivationOutcome.ACTIVATED, useCase.activate(draft))
        assertSame(view, useCase.viewCode())

        assertEquals(
            listOf(
                AuditEventType.ENCRYPTION_CHANGED to AuditResult.SUCCESS,
                AuditEventType.RECOVERY_CODE_VIEWED to AuditResult.SUCCESS,
            ),
            audits,
        )
    }
}
