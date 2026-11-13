package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Cuántos grupos tiene el código de recuperación, y cuántos caracteres cada uno. */
const val RECOVERY_CODE_GROUPS = 7
const val RECOVERY_CODE_GROUP_LENGTH = 4

/**
 * El código de recuperación escrito a mano, en 7 casillas de 4 caracteres.
 *
 * - **Nunca sale de aquí.** Va dentro de [PrivateInput]: ni copiar ni cortar dejan nada en el
 *   portapapeles, y el teclado no aprende lo escrito. Teclado de contraseña, sin autocorrección ni
 *   sugerencias, y cada casilla marcada como dato sensible.
 * - **Avanza solo.** Al completar 4 caracteres pasa a la casilla siguiente. Pegar o escribir un
 *   código entero en una casilla lo reparte entre las siguientes.
 * - **Marca, no borra.** [errorGroup] marca el grupo que no coincide, sin tocar lo escrito.
 * - **Se lee entero con cualquier letra.** Cada casilla mide al menos lo que ocupan 4 de los
 *   caracteres más anchos del código con la letra actual: con la letra grande crecen, y caben menos
 *   por línea, pero ningún carácter queda cortado. Un código que no se lee es un código perdido.
 *
 * Solo admite letras y cifras, en mayúsculas; los espacios y guiones de un código pegado se quitan.
 * Comprobar el código es de quien lo usa.
 *
 * @param groups los 7 grupos escritos, cada uno de hasta 4 caracteres.
 * @param errorGroup el índice del grupo con error, o `null`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoveryCodeField(
    groups: List<String>,
    onGroupsChange: (List<String>) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorGroup: Int? = null,
    error: String? = null,
) {
    val focus = remember { List(RECOVERY_CODE_GROUPS) { FocusRequester() } }
    // El foco avanza después del cambio, no dentro de él: moverlo a mitad de una pegada la deshacía.
    var moveTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(moveTo) {
        moveTo?.let {
            focus[it].requestFocus()
            moveTo = null
        }
    }
    val spacing = AppTheme.spacing
    val textStyle = AppTheme.amounts.row
    val groupWidth = recoveryCodeGroupWidth(textStyle)
    PrivateInput {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                for (index in 0 until RECOVERY_CODE_GROUPS) {
                    val groupLabel =
                        stringResource(
                            R.string.a11y_recovery_code_group,
                            index + 1,
                            RECOVERY_CODE_GROUPS,
                        )
                    OutlinedTextField(
                        value = groups.getOrElse(index) { "" },
                        onValueChange = { typed ->
                            val next = RecoveryCodeInput.spread(groups, index, typed)
                            onGroupsChange(next)
                            val landed = RecoveryCodeInput.focusAfter(next, index, typed)
                            if (landed != index) moveTo = landed
                        },
                        modifier =
                            Modifier
                                .width(groupWidth)
                                .focusRequester(focus[index])
                                .semantics {
                                    contentDescription = groupLabel
                                    isSensitiveData = true
                                },
                        textStyle = textStyle,
                        isError = errorGroup == index,
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                                imeAction =
                                    if (index == RECOVERY_CODE_GROUPS - 1) {
                                        ImeAction.Done
                                    } else {
                                        ImeAction.Next
                                    },
                            ),
                        shape = MaterialTheme.shapes.medium,
                        colors = appFieldColors(),
                    )
                }
            }
            if (error != null) FieldError(error)
        }
    }
}

/**
 * El ancho de una casilla: el de 4 de los caracteres más anchos del código en [style], con la letra
 * actual, más el relleno del campo, y nunca menos que el ancho de diseño.
 */
@Composable
private fun recoveryCodeGroupWidth(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minimum = AppTheme.sizes.recoveryCodeGroup
    return remember(measurer, style, density, minimum) {
        val widest =
            WIDEST_GROUPS.maxOf { sample -> measurer.measure(sample, style).size.width }
        maxOf(minimum, with(density) { widest.toDp() } + FIELD_HORIZONTAL_PADDING)
    }
}

/** Los grupos más anchos que puede tener el código, en Crockford Base32. */
private val WIDEST_GROUPS = listOf("WWWW", "MMMM", "0000")

/** El relleno a los dos lados del texto de un campo con borde, y un margen para el cursor. */
private val FIELD_HORIZONTAL_PADDING = 36.dp

/** Las reglas de escritura de [RecoveryCodeField], aparte para probarlas sin pantalla. */
object RecoveryCodeInput {
    /**
     * Los grupos tras escribir [typed] en el grupo [index]: se limpia, y lo que sobra de 4
     * caracteres pasa a los grupos siguientes, hasta el último. Lo que no cabe se descarta.
     */
    fun spread(groups: List<String>, index: Int, typed: String): List<String> {
        val result = MutableList(RECOVERY_CODE_GROUPS) { groups.getOrElse(it) { "" } }
        var rest = clean(typed)
        var at = index
        result[at] = rest.take(RECOVERY_CODE_GROUP_LENGTH)
        rest = rest.drop(RECOVERY_CODE_GROUP_LENGTH)
        while (rest.isNotEmpty() && at < RECOVERY_CODE_GROUPS - 1) {
            at += 1
            result[at] = rest.take(RECOVERY_CODE_GROUP_LENGTH)
            rest = rest.drop(RECOVERY_CODE_GROUP_LENGTH)
        }
        return result
    }

    /**
     * La casilla que queda con el foco: la siguiente a la última que se llenó con lo escrito, o la
     * misma si quedó a medias o ya era la última.
     */
    fun focusAfter(groups: List<String>, index: Int, typed: String): Int {
        val written = clean(typed).length
        val filled = (written / RECOVERY_CODE_GROUP_LENGTH).coerceAtLeast(0)
        if (filled == 0) return index
        val last = (index + filled - 1).coerceAtMost(RECOVERY_CODE_GROUPS - 1)
        val complete = groups.getOrElse(last) { "" }.length == RECOVERY_CODE_GROUP_LENGTH
        return if (complete) (last + 1).coerceAtMost(RECOVERY_CODE_GROUPS - 1) else index
    }

    private fun clean(text: String): String =
        text.filter { it.isLetterOrDigit() && it.code < ASCII_LIMIT }.uppercase()

    private const val ASCII_LIMIT = 128
}

@ThemePreviews
@Composable
private fun RecoveryCodeFieldPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            RecoveryCodeField(
                groups = listOf("7KQ2", "M9XA", "R4TB", "W8", "", "", ""),
                onGroupsChange = {},
                label = stringResource(R.string.recovery_field_label),
                errorGroup = 1,
                error = stringResource(R.string.recovery_error_typo, 2),
            )
        }
    }
}
