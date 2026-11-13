package io.github.yesidrobayo.shopledger.domain.rule.customer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El recordatorio de pago: la plantilla se llena con los datos del cliente y nada se envía aquí. */
class ReminderRulesTest {
    private val values = ReminderValues(
        customer = "Cliente de prueba",
        amount = "¤ 4.000",
        business = "Tienda de prueba",
        dueDate = "30 nov 2026",
    )

    @Test
    fun `the default template is filled with the customer, the amount and the business`() {
        val template =
            "Hola, {customer}. Este es un recordatorio de {business}: el saldo pendiente" +
                " es {amount}. Gracias."

        assertEquals(
            "Hola, Cliente de prueba. Este es un recordatorio de Tienda de prueba: el saldo" +
                " pendiente es ¤ 4.000. Gracias.",
            ReminderRules.fill(template, values, details = null),
        )
    }

    @Test
    fun `balance is the same as amount, and a missing due date leaves its mark empty`() {
        assertEquals(
            "Debe ¤ 4.000 desde .",
            ReminderRules.fill(
                "Debe {balance} desde {due_date}.",
                values.copy(dueDate = null),
                null,
            ),
        )
        assertEquals(
            "Vence 30 nov 2026",
            ReminderRules.fill("Vence {due_date}", values, "  "),
        )
    }

    @Test
    fun `payment details go at the end, after a blank line`() {
        assertEquals(
            "Debe ¤ 4.000\n\nCuenta 000-000",
            ReminderRules.fill("Debe {amount}", values, " Cuenta 000-000 "),
        )
    }

    @Test
    fun `an empty or too long template is not saved`() {
        assertEquals(ReminderTemplateIssue.EMPTY, ReminderRules.check("   "))
        assertEquals(ReminderTemplateIssue.TOO_LONG, ReminderRules.check("a".repeat(1_001)))
        assertNull(ReminderRules.check("a".repeat(1_000)))
    }
}
