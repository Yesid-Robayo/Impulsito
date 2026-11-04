package io.github.yesidrobayo.shopledger.domain.model

import kotlin.time.Instant

/**
 * Los datos de la anulación de un documento: lo único que cambia en él después de confirmarse.
 *
 * [voidedAt] es el instante de la anulación, con precisión de milisegundos, que es como se guarda.
 * [note] es opcional, y la escribe la persona para explicar el motivo.
 */
data class Voiding(val voidedAt: Instant, val reason: VoidReason, val note: String?)
