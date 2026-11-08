package io.github.yesidrobayo.shopledger.domain.model.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Las clases de método de pago son genéricas, y se leen solo por su nombre exacto. */
class PaymentMethodKindTest {
    @Test
    fun `the kinds are generic, with no brand or country`() {
        // Son valores guardados en la base. Una marca o un medio de un país es un método de pago
        // que la persona crea con su nombre, nunca una clase aquí.
        assertEquals(
            listOf("CASH", "CARD", "TRANSFER", "WALLET", "ON_ACCOUNT", "OTHER"),
            PaymentMethodKind.entries.map { it.name },
        )
    }

    @Test
    fun `a stored kind is read by its exact name`() {
        for (kind in PaymentMethodKind.entries) {
            assertEquals(kind, PaymentMethodKind.fromStoredOrNull(kind.name))
        }
        assertNull(PaymentMethodKind.fromStoredOrNull("cash"))
        assertNull(PaymentMethodKind.fromStoredOrNull("VOUCHER"))
        assertNull(PaymentMethodKind.fromStoredOrNull(""))
    }

    @Test
    fun `an unknown stored kind is read as other`() {
        // Una copia de una versión más nueva puede traer un medio que esta no conoce: el método se
        // sigue usando, como otro medio, en vez de tratarse como daño.
        for (kind in PaymentMethodKind.entries) {
            assertEquals(kind, PaymentMethodKind.fromStoredOrOther(kind.name))
        }
        assertEquals(PaymentMethodKind.OTHER, PaymentMethodKind.fromStoredOrOther("VOUCHER"))
        assertEquals(PaymentMethodKind.OTHER, PaymentMethodKind.fromStoredOrOther("cash"))
        assertEquals(PaymentMethodKind.OTHER, PaymentMethodKind.fromStoredOrOther(""))
    }
}
