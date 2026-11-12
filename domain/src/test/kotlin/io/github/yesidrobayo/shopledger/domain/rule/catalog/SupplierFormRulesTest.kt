package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import org.junit.Assert.assertEquals
import org.junit.Test

/** El nombre, el teléfono, el correo, las notas y el plazo de un proveedor antes de guardarlo. */
class SupplierFormRulesTest {
    @Test
    fun `only a name is enough, and blank optional data is kept without value`() {
        assertEquals(
            SupplierFormCheck.Valid(
                SupplierDetails("Granja El Alto", "granja el alto", null, null, null, null),
            ),
            SupplierFormRules.check(SupplierForm(" Granja El Alto ", "  ", "", " \t ")),
        )
    }

    @Test
    fun `every datum is trimmed and kept, with its payment terms`() {
        val check =
            SupplierFormRules.check(
                SupplierForm(
                    name = "Distribuidora Ñandú",
                    phone = " +1 (555) 010-0199 ",
                    email = " compras@example.com ",
                    notes = " Entrega los martes ",
                    paymentTermsDays = 30,
                ),
            )

        assertEquals(
            SupplierFormCheck.Valid(
                SupplierDetails(
                    name = "Distribuidora Ñandú",
                    normalizedName = "distribuidora ñandu",
                    phone = "+1 (555) 010-0199",
                    email = "compras@example.com",
                    notes = "Entrega los martes",
                    paymentTermsDays = 30,
                ),
            ),
            check,
        )
    }

    @Test
    fun `digits of any script are a phone, with no assumed length or prefix`() {
        for (phone in listOf("٠١٢٣٤٥", "5", "0-0", "१२३ ४५६")) {
            assertEquals(phone, validDetails(SupplierForm("A", phone = phone)).phone)
        }
    }

    @Test
    fun `a phone with letters, other signs or no digit is rejected`() {
        for (phone in listOf("555-CALL", "555.010", "+ - ( )", "#555")) {
            assertEquals(
                phone,
                SupplierFormCheck.Invalid(setOf(SupplierFormIssue.PHONE_FORMAT)),
                SupplierFormRules.check(SupplierForm("A", phone = phone)),
            )
        }
    }

    @Test
    fun `an email needs exactly one at sign with text on each side`() {
        for (email in listOf("a@b", "x y@z")) {
            assertEquals(email, validDetails(SupplierForm("A", email = email)).email)
        }
        for (email in listOf("compras", "@example.com", "compras@", "a@b@c", "a @ ")) {
            assertEquals(
                email,
                SupplierFormCheck.Invalid(setOf(SupplierFormIssue.EMAIL_FORMAT)),
                SupplierFormRules.check(SupplierForm("A", email = email)),
            )
        }
    }

    @Test
    fun `lengths are counted in characters up to each limit`() {
        // Un emoji ocupa dos unidades de UTF-16 pero es un carácter, como lo cuenta la base.
        val details =
            validDetails(
                SupplierForm(
                    name = "😀".repeat(80),
                    phone = "1".repeat(30),
                    email = "a@" + "b".repeat(117),
                    notes = "😀".repeat(500),
                ),
            )

        assertEquals(80, details.name.codePointCount(0, details.name.length))
    }

    @Test
    fun `every reason is reported at once`() {
        assertEquals(
            SupplierFormCheck.Invalid(
                setOf(
                    SupplierFormIssue.NAME_TOO_LONG,
                    SupplierFormIssue.PHONE_TOO_LONG,
                    SupplierFormIssue.PHONE_FORMAT,
                    SupplierFormIssue.EMAIL_TOO_LONG,
                    SupplierFormIssue.EMAIL_FORMAT,
                    SupplierFormIssue.NOTES_TOO_LONG,
                    SupplierFormIssue.PAYMENT_TERMS_OUT_OF_RANGE,
                ),
            ),
            SupplierFormRules.check(
                SupplierForm(
                    name = "a".repeat(81),
                    phone = "x".repeat(31),
                    email = "a".repeat(121),
                    notes = "a".repeat(501),
                    paymentTermsDays = 3_651,
                ),
            ),
        )
    }

    @Test
    fun `an empty name and a negative term are rejected, and the term edges are valid`() {
        assertEquals(
            SupplierFormCheck.Invalid(
                setOf(SupplierFormIssue.NAME_EMPTY, SupplierFormIssue.PAYMENT_TERMS_OUT_OF_RANGE),
            ),
            SupplierFormRules.check(SupplierForm("  ", paymentTermsDays = -1)),
        )
        assertEquals(0, validDetails(SupplierForm("A", paymentTermsDays = 0)).paymentTermsDays)
        assertEquals(
            3_650,
            validDetails(SupplierForm("A", paymentTermsDays = 3_650)).paymentTermsDays,
        )
    }

    private fun validDetails(form: SupplierForm): SupplierDetails =
        (SupplierFormRules.check(form) as SupplierFormCheck.Valid).details
}
