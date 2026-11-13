package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Los datos mínimos de un cliente: solo el nombre es obligatorio, y los largos de la base. */
class CustomerFormRulesTest {
    @Test
    fun `only a name is enough, trimmed and normalized, and blanks are stored as nothing`() {
        assertEquals(
            CustomerFormCheck.Valid(
                CustomerDetails(CustomerContact("José Ríos", "jose rios", null, null, null), null),
            ),
            CustomerFormRules.check(
                CustomerForm("  José Ríos ", phone = " ", email = "", notes = "  "),
            ),
        )
    }

    @Test
    fun `optional data and the price list are kept trimmed`() {
        assertEquals(
            CustomerFormCheck.Valid(
                CustomerDetails(
                    CustomerContact(
                        "Ana",
                        "ana",
                        "+1 (555) 010-0199",
                        "ana@example.com",
                        "Paga los viernes",
                    ),
                    priceListId = 3L,
                ),
            ),
            CustomerFormRules.check(
                CustomerForm(
                    "Ana",
                    phone = " +1 (555) 010-0199 ",
                    email = " ana@example.com ",
                    notes = " Paga los viernes ",
                    priceListId = 3L,
                ),
            ),
        )
    }

    @Test
    fun `digits of any writing system make a phone`() {
        val check = CustomerFormRules.check(CustomerForm("Ana", phone = "٣٠٠ ١٢٣"))

        assertEquals("٣٠٠ ١٢٣", (check as CustomerFormCheck.Valid).details.contact.phone)
    }

    @Test
    fun `an empty or too long name is rejected`() {
        assertEquals(
            CustomerFormCheck.Invalid(setOf(CustomerFormIssue.NAME_EMPTY)),
            CustomerFormRules.check(CustomerForm("   ")),
        )
        assertEquals(
            CustomerFormCheck.Invalid(setOf(CustomerFormIssue.NAME_TOO_LONG)),
            CustomerFormRules.check(CustomerForm("a".repeat(81))),
        )
        // 80 caracteres, uno de ellos un emoji de dos unidades, todavía caben.
        val eighty = "a".repeat(79) + "😀"
        assertEquals(
            eighty,
            (CustomerFormRules.check(CustomerForm(eighty)) as CustomerFormCheck.Valid)
                .details.contact.name,
        )
    }

    @Test
    fun `every reason is reported at once`() {
        assertEquals(
            CustomerFormCheck.Invalid(
                setOf(
                    CustomerFormIssue.NAME_EMPTY,
                    CustomerFormIssue.PHONE_TOO_LONG,
                    CustomerFormIssue.PHONE_FORMAT,
                    CustomerFormIssue.EMAIL_TOO_LONG,
                    CustomerFormIssue.EMAIL_FORMAT,
                    CustomerFormIssue.NOTES_TOO_LONG,
                ),
            ),
            CustomerFormRules.check(
                CustomerForm(
                    "",
                    phone = "1".repeat(30) + "x",
                    email = "a".repeat(121),
                    notes = "n".repeat(501),
                ),
            ),
        )
    }

    @Test
    fun `phone and email formats help typing`() {
        val cases = mapOf(
            CustomerForm("Ana", phone = "uno") to CustomerFormIssue.PHONE_FORMAT,
            CustomerForm("Ana", phone = "+ - ( )") to CustomerFormIssue.PHONE_FORMAT,
            CustomerForm("Ana", email = "ana") to CustomerFormIssue.EMAIL_FORMAT,
            CustomerForm("Ana", email = "a@b@c") to CustomerFormIssue.EMAIL_FORMAT,
            CustomerForm("Ana", email = "@example.com") to CustomerFormIssue.EMAIL_FORMAT,
            CustomerForm("Ana", email = "ana@ ") to CustomerFormIssue.EMAIL_FORMAT,
        )
        cases.forEach { (form, issue) ->
            assertEquals(
                form.toString(),
                CustomerFormCheck.Invalid(setOf(issue)),
                CustomerFormRules.check(form),
            )
        }
    }

    @Test
    fun `the limits themselves are accepted`() {
        val check = CustomerFormRules.check(
            CustomerForm(
                "a".repeat(80),
                phone = "1".repeat(30),
                email = "a".repeat(59) + "@" + "b".repeat(60),
                notes = "n".repeat(500),
            ),
        )

        assertEquals(true, check is CustomerFormCheck.Valid)
    }
}
