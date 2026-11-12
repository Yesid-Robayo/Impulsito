package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.backup.ResealOutcome
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

    /**
     * Sella [draft] y enciende el cifrado, o lo cambia por el código vigente. Antes de sellar, un
     * fallo no cambia nada, y las copias anteriores siguen necesitando su código.
     */
    suspend fun activate(draft: RecoveryCodeDraft): ActivationOutcome

    /**
     * Vuelve a guardar en este teléfono el código vigente, escrito por la persona, cuando el que
     * estaba ya no abre. No cambia de código ni borra nada.
     */
    suspend fun reseal(typed: String): ResealOutcome

    /**
     * El código sellado en este teléfono, para `Ver mi código`. Solo abre el sobre: no escribe nada,
     * y sigue disponible con el cifrado apagado, porque las copias cifradas que quedan lo necesitan.
     */
    suspend fun viewCode(): RecoveryCodeView
}
