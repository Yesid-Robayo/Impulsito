package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Qué texto lleva cada error de campo del formulario de un producto. Los nombres son inventados. */
class ProductEditMessagesTest {
    @Test
    fun `a repeated name names the product that already has it`() {
        assertEquals(
            FieldMessage.Text(R.string.error_name_duplicate, listOf("Jabón Líquido")),
            fieldMessageOf(FieldProblem.Duplicate("Jabón Líquido"), ProductEditField.NAME),
        )
    }

    @Test
    fun `a repeated barcode names its product and never talks about the name`() {
        val message =
            fieldMessageOf(FieldProblem.Duplicate("Arroz 1 kg"), ProductEditField.BARCODE)

        assertEquals(
            FieldMessage.Text(R.string.error_barcode_duplicate, listOf("Arroz 1 kg")),
            message,
        )
        assertNotEquals(R.string.error_name_duplicate, (message as FieldMessage.Text).res)
    }

    @Test
    fun `a long name has its own text and other long fields say their maximum`() {
        assertEquals(
            FieldMessage.Text(R.string.error_name_long),
            fieldMessageOf(FieldProblem.TooLong(80), ProductEditField.NAME),
        )
        assertEquals(
            FieldMessage.Plural(R.plurals.error_text_long, 64),
            fieldMessageOf(FieldProblem.TooLong(64), ProductEditField.BARCODE),
        )
    }

    @Test
    fun `numbers, limits and units keep their texts`() {
        assertEquals(
            FieldMessage.Text(R.string.error_name_empty),
            fieldMessageOf(FieldProblem.Empty, ProductEditField.NAME),
        )
        assertEquals(
            FieldMessage.Text(R.string.field_error_number),
            fieldMessageOf(FieldProblem.NotANumber, ProductEditField.SALE_PRICE),
        )
        assertEquals(
            FieldMessage.Text(R.string.field_error_number),
            fieldMessageOf(FieldProblem.TooManyDecimals(2), ProductEditField.COST),
        )
        assertEquals(
            FieldMessage.Text(R.string.error_value_high),
            fieldMessageOf(FieldProblem.TooHigh, ProductEditField.INITIAL_STOCK),
        )
        assertEquals(
            FieldMessage.Text(R.string.error_unit_decimals),
            fieldMessageOf(FieldProblem.FractionNotAllowed, ProductEditField.MIN_STOCK),
        )
    }
}
