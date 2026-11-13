package io.github.yesidrobayo.shopledger.domain.model.money

/**
 * Qué clase de lugar guarda el dinero de una cuenta. Solo orienta a la persona y al icono: las reglas
 * del libro son las mismas para todas. Los nombres se guardan tal cual y no cambian nunca.
 */
enum class MoneyAccountKind {
    /** Efectivo: la caja o el cajón. */
    CASH,

    /** Una cuenta en un banco. */
    BANK,

    /** Una billetera del teléfono o de una tienda de pagos. */
    WALLET,

    /** Cualquier otro lugar. */
    OTHER,
}
