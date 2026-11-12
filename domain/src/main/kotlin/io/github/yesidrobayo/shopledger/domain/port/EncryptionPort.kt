package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck

/**
 * El cifrado opcional de las copias, como lo ve la pantalla del cifrado: su estado y los pasos de
 * la activación. Lo implementa la capa de datos, que es la única que conoce el formato y la
 * custodia. Nada de esto usa la red.
 */
interface EncryptionPort {
    /** Si las copias salen cifradas y con qué código. Solo lee archivos locales. */
    suspend fun status(): EncryptionStatus

    /** Un código nuevo, que vive solo en memoria hasta [activate]. */
    fun newCode(): RecoveryCodeDraft

    /** Compara lo escrito con [draft], ya normalizado, y dice dónde falla si no coincide. */
    fun checkTyped(draft: RecoveryCodeDraft, typed: String): TypedCodeCheck

    /** La prueba de ida y vuelta con la clave de [draft], sin tocar la base ni el disco. */
    suspend fun roundTrip(draft: RecoveryCodeDraft): Boolean

    /** Sella [draft] y enciende el cifrado. Antes de sellar, un fallo no cambia nada. */
    suspend fun activate(draft: RecoveryCodeDraft): ActivationOutcome
}
