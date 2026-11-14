package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ExtraNameIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los cuatro tipos de campo propio, los nombres y la búsqueda por etiqueta, con valores a mano. */
class CustomFieldRulesTest {
    @Test
    fun `a text keeps up to two hundred characters, trimmed`() {
        assertEquals(valid("Hecho a mano"), value(CustomFieldType.TEXT, "  Hecho a mano "))
        assertEquals(valid("a".repeat(200)), value(CustomFieldType.TEXT, "a".repeat(200)))
        assertEquals(
            invalid(CustomValueIssue.TOO_LONG),
            value(CustomFieldType.TEXT, "a".repeat(201)),
        )
    }

    @Test
    fun `a number is stored in its neutral form without extra zeros`() {
        assertEquals(valid("1.5"), value(CustomFieldType.NUMBER, "001.50"))
        assertEquals(valid("-12"), value(CustomFieldType.NUMBER, "-12.000"))
        assertEquals(valid("0"), value(CustomFieldType.NUMBER, "-0.0"))
        assertEquals(valid("0.25"), value(CustomFieldType.NUMBER, "0.250"))
        assertEquals(invalid(CustomValueIssue.NOT_A_NUMBER), value(CustomFieldType.NUMBER, ".25"))
        // Con separador de miles, con coma o con letras no es un número neutro.
        assertEquals(invalid(CustomValueIssue.NOT_A_NUMBER), value(CustomFieldType.NUMBER, "1,5"))
        assertEquals(invalid(CustomValueIssue.NOT_A_NUMBER), value(CustomFieldType.NUMBER, "1e3"))
        assertEquals(
            invalid(CustomValueIssue.NOT_A_NUMBER),
            value(CustomFieldType.NUMBER, "1".repeat(19)),
        )
        assertEquals(
            invalid(CustomValueIssue.NOT_A_NUMBER),
            value(CustomFieldType.NUMBER, "1." + "1".repeat(19)),
        )
    }

    @Test
    fun `a date is a real day of the calendar`() {
        assertEquals(valid("2028-02-29"), value(CustomFieldType.DATE, "2028-02-29"))
        assertEquals(
            invalid(CustomValueIssue.NOT_A_DATE),
            value(CustomFieldType.DATE, "2027-02-29"),
        )
        assertEquals(
            invalid(CustomValueIssue.NOT_A_DATE),
            value(CustomFieldType.DATE, "14/11/2026"),
        )
    }

    @Test
    fun `a yes or no is true or false`() {
        assertEquals(valid("true"), value(CustomFieldType.BOOLEAN, "true"))
        assertEquals(valid("false"), value(CustomFieldType.BOOLEAN, "false"))
        assertEquals(invalid(CustomValueIssue.NOT_A_BOOLEAN), value(CustomFieldType.BOOLEAN, "sí"))
    }

    @Test
    fun `a blank value clears and an unknown type is not written`() {
        for (type in CustomFieldType.entries) {
            assertEquals(CustomValueCheck.Blank, CustomFieldRules.value(type, "   "))
        }
        assertEquals(invalid(CustomValueIssue.UNKNOWN_TYPE), value(null, "algo"))
        assertNull(CustomFieldType.fromStored("COLOR"))
        assertEquals(CustomFieldType.DATE, CustomFieldType.fromStored("DATE"))
    }

    @Test
    fun `a name has one to forty characters`() {
        assertEquals(
            ExtraNameCheck.Valid("Garantía", "garantia"),
            CustomFieldRules.name(" Garantía "),
        )
        assertEquals(ExtraNameCheck.Invalid(ExtraNameIssue.EMPTY), CustomFieldRules.name("  "))
        assertEquals(
            ExtraNameCheck.Invalid(ExtraNameIssue.TOO_LONG),
            CustomFieldRules.name("x".repeat(41)),
        )
    }

    @Test
    fun `the search finds a product by one of its tags`() {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Jabón de avena")
        fixture.seedProduct(2L, "Vela de soya")
        val products = listOf(fixture.product(1L), fixture.product(2L))
        val tags = mapOf(2L to listOf("hecho a mano", "regalo"))

        assertEquals(listOf(2L), ProductSearch.filter(products, "Regalo", tags).map { it.id })
        assertEquals(listOf(1L), ProductSearch.filter(products, "avena", tags).map { it.id })
        assertEquals(emptyList<Long>(), ProductSearch.filter(products, "regalo").map { it.id })
        assertEquals(
            listOf<ProductDetails>(),
            ProductSearch.filter(products, "nada", tags).map { it.details },
        )
    }

    private fun value(type: CustomFieldType?, raw: String) = CustomFieldRules.value(type, raw)

    private fun valid(stored: String) = CustomValueCheck.Valid(stored)

    private fun invalid(issue: CustomValueIssue) = CustomValueCheck.Invalid(issue)
}
