package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort

/**
 * La pantalla del cifrado sobre la custodia y la activación. Traduce entre el código impreso, que
 * ve la pantalla, y el código canónico, que solo vive en esta capa.
 */
internal class EncryptionService(
    private val custody: RecoveryCustody,
    private val activation: EncryptionActivation,
) : EncryptionPort {
    override suspend fun status(): EncryptionStatus {
        val state = custody.alignAtStartup()
        if (!state.enabled) {
            return EncryptionStatus.Off(
                wasOn =
                    state.offConfirmedAtUtcMillis != null,
            )
        }
        return when (custody.keys.open()) {
            is RecoveryKeyRead.Available -> EncryptionStatus.On(state.enabledAtUtcMillis)

            // Un fallo pasajero no detiene nada: el respaldo reintenta.
            is RecoveryKeyRead.RetryLater -> EncryptionStatus.On(state.enabledAtUtcMillis)

            RecoveryKeyRead.Absent,
            is RecoveryKeyRead.Unavailable,
            -> EncryptionStatus.KeyUnavailable
        }
    }

    override fun newCode(): RecoveryCodeDraft = draftOf(activation.newCode())

    override fun checkTyped(draft: RecoveryCodeDraft, typed: String): TypedCodeCheck {
        val expected = draft.groups.joinToString("")
        val normalized = RecoveryCodec.normalize(typed)
        if (normalized.length != expected.length) return TypedCodeCheck.Incomplete
        val group =
            (0 until RecoveryCodeDraft.GROUPS).firstOrNull { index ->
                val range = index * GROUP until (index + 1) * GROUP
                normalized.substring(range) != expected.substring(range)
            }
        return if (group ==
            null
        ) {
            TypedCodeCheck.Matches
        } else {
            TypedCodeCheck.GroupMismatch(group + 1)
        }
    }

    override suspend fun roundTrip(draft: RecoveryCodeDraft): Boolean =
        codeOf(draft)?.let { activation.roundTrip(it) } ?: false

    override suspend fun activate(draft: RecoveryCodeDraft): ActivationOutcome {
        val code = codeOf(draft) ?: return ActivationOutcome.SAVE_FAILED
        return when (activation.activate(code)) {
            is ActivationResult.Activated -> ActivationOutcome.ACTIVATED
            ActivationResult.SealFailed -> ActivationOutcome.SAVE_FAILED
        }
    }

    private fun draftOf(code: RecoveryCode): RecoveryCodeDraft =
        RecoveryCodeDraft(RecoveryCodec.printable(code).split('-'))

    /** El código de un borrador; `null` si su control no cuadra, lo que no debería pasar. */
    private fun codeOf(draft: RecoveryCodeDraft): RecoveryCode? =
        (RecoveryCodec.parse(draft.groups.joinToString("")) as? CodeParseResult.Valid)?.code

    private companion object {
        const val GROUP = RecoveryCodeDraft.GROUP_LENGTH
    }
}
