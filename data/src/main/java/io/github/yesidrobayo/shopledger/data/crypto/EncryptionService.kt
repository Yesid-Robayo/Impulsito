package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.CodeCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.backup.ResealOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort
import java.io.IOException

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
            is RecoveryKeyRead.Available ->
                EncryptionStatus.On(state.enabledAtUtcMillis, state.lastCodeCheckAtUtcMillis)

            // Un fallo pasajero no detiene nada: el respaldo reintenta.
            is RecoveryKeyRead.RetryLater ->
                EncryptionStatus.On(state.enabledAtUtcMillis, state.lastCodeCheckAtUtcMillis)

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

    override suspend fun reseal(typed: String): ResealOutcome =
        when (val parsed = RecoveryCodec.parse(typed)) {
            CodeParseResult.WrongLength,
            CodeParseResult.InvalidCharacter,
            CodeParseResult.CheckMismatch,
            -> ResealOutcome.TYPO

            is CodeParseResult.Valid ->
                when (activation.reseal(parsed.code)) {
                    ResealResult.Sealed -> ResealOutcome.SEALED

                    ResealResult.OtherCode -> ResealOutcome.OTHER_CODE

                    // Sin cifrado activo no hay nada que sellar: se dice como fallo, sin cambiar nada.
                    ResealResult.NotNeeded, ResealResult.SealFailed -> ResealOutcome.SAVE_FAILED
                }
        }

    override suspend fun viewCode(): RecoveryCodeView = when (val read = custody.keys.open()) {
        is RecoveryKeyRead.Available -> RecoveryCodeView.Shown(draftOf(read.code))

        // Sin sobre, o con uno que no abre, no hay código que mostrar. No se reintenta aquí: la
        // persona puede volver a tocar `Ver mi código`.
        RecoveryKeyRead.Absent,
        is RecoveryKeyRead.Unavailable,
        is RecoveryKeyRead.RetryLater,
        -> RecoveryCodeView.Unavailable
    }

    override suspend fun checkCode(typed: String): CodeCheckOutcome =
        when (val read = custody.keys.open()) {
            is RecoveryKeyRead.Available ->
                if (checkTyped(draftOf(read.code), typed) == TypedCodeCheck.Matches) {
                    // El código es correcto aunque el disco no deje anotar la fecha: la prueba se
                    // volverá a pedir, y nada más cambia.
                    try {
                        custody.recordCodeCheck(read.key.keyIdHex)
                    } catch (_: IOException) {
                        false
                    }
                    CodeCheckOutcome.CORRECT
                } else {
                    CodeCheckOutcome.MISMATCH
                }

            RecoveryKeyRead.Absent,
            is RecoveryKeyRead.Unavailable,
            is RecoveryKeyRead.RetryLater,
            -> CodeCheckOutcome.UNAVAILABLE
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
