package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Voiding

/**
 * Una compra guardada. Nunca se edita: solo se anula, y entonces [voiding] guarda cuándo y por qué.
 */
data class Purchase(val id: Long, val content: NewPurchase, val voiding: Voiding?) {
    /** Activa o anulada, según [voiding]. */
    val status: DocumentStatus get() = DocumentStatus.of(voiding)
}
