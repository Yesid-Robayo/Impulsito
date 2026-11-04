package io.github.yesidrobayo.shopledger.domain.model.payment

/**
 * La clase de un método de pago, que decide cómo se cobra: en efectivo, a crédito o por otro medio.
 * Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * Los métodos de pago son **datos** que la persona crea y nombra, no una lista de marcas: aquí no
 * hay billeteras, bancos ni redes de un país. Por eso un valor guardado desconocido no es daño de
 * la copia: se trata como [OTHER] y se cuenta como aviso.
 */
enum class PaymentMethodKind {
    /** Efectivo: entra a la caja y admite redondeo de efectivo. */
    CASH,

    /** Tarjeta, de cualquier red. */
    CARD,

    /** Transferencia, de cualquier banco o sistema. */
    TRANSFER,

    /** Billetera digital, de cualquier proveedor. */
    WALLET,

    /** A crédito: queda como deuda de quien compra. */
    ON_ACCOUNT,

    /** Cualquier otro medio. */
    OTHER,
    ;

    companion object {
        /**
         * La clase guardada como [stored], o `null` si no es ninguna de esta lista.
         *
         * Compara el nombre exacto: el valor viene de la base o de una copia, que no son confiables.
         */
        fun fromStoredOrNull(stored: String): PaymentMethodKind? =
            entries.firstOrNull { it.name == stored }
    }
}
