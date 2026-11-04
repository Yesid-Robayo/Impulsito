package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Voiding

/**
 * Una venta guardada. Nunca se edita: solo se anula, y entonces [voiding] guarda cuándo y por qué.
 */
data class Sale(val id: Long, val content: NewSale, val voiding: Voiding?) {
    /** Activa o anulada, según [voiding]. */
    val status: DocumentStatus get() = DocumentStatus.of(voiding)
}
