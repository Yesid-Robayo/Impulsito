package io.github.yesidrobayo.shopledger.ui.product

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R

/**
 * El texto de un error de campo, todavía sin resolver: qué recurso y con qué valores.
 *
 * Elegirlo es una función pura, para probar en la JVM que cada aviso dice lo que debe, como el nombre
 * del producto con que choca, sin dibujar la pantalla.
 */
sealed interface FieldMessage {
    /** Un texto con sus marcadores `%1$s`… llenos con [args]. */
    data class Text(@param:StringRes val res: Int, val args: List<String> = emptyList()) :
        FieldMessage

    /** Un texto que cambia con [count], como un largo máximo. */
    data class Plural(@param:PluralsRes val res: Int, val count: Int) : FieldMessage
}

/**
 * El mensaje de [problem] en [field]. Un nombre y un código repetidos dicen los dos qué producto ya
 * los usa, cada uno con su frase: un código repetido nunca habla del nombre.
 */
fun fieldMessageOf(problem: FieldProblem, field: ProductEditField): FieldMessage = when (problem) {
    FieldProblem.Empty -> FieldMessage.Text(R.string.error_name_empty)

    is FieldProblem.TooLong ->
        if (field == ProductEditField.NAME) {
            FieldMessage.Text(R.string.error_name_long)
        } else {
            FieldMessage.Plural(R.plurals.error_text_long, problem.max)
        }

    is FieldProblem.Duplicate ->
        if (field == ProductEditField.BARCODE) {
            FieldMessage.Text(R.string.error_barcode_duplicate, listOf(problem.existingName))
        } else {
            FieldMessage.Text(R.string.error_name_duplicate, listOf(problem.existingName))
        }

    FieldProblem.NotANumber, is FieldProblem.TooManyDecimals ->
        FieldMessage.Text(R.string.field_error_number)

    FieldProblem.TooHigh -> FieldMessage.Text(R.string.error_value_high)

    FieldProblem.FractionNotAllowed -> FieldMessage.Text(R.string.error_unit_decimals)
}

/** El texto de [problem] en [field], en el idioma de la interfaz. */
@Composable
fun problemText(problem: FieldProblem, field: ProductEditField): String =
    when (val message = fieldMessageOf(problem, field)) {
        is FieldMessage.Text ->
            stringResource(message.res, *message.args.toTypedArray())

        is FieldMessage.Plural ->
            pluralStringResource(message.res, message.count, message.count)
    }
