package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant

/**
 * Contar el efectivo pieza por pieza: una fila por billete o moneda de la persona, con su valor, las
 * piezas que se cuentan y el subtotal a la derecha, y `Total contado` al final.
 *
 * Las piezas se escriben con el teclado numérico: contar decenas de billetes con un botón de más
 * sería lento. Lo que se está llenando nunca se oculta con el modo discreto, para no guardar un
 * error; el total tampoco, porque es lo que se cuenta en ese momento. Las denominaciones son las que
 * escribió la persona: no hay ninguna inicial.
 *
 * @param counts las piezas escritas por id de denominación, tal como se escribieron.
 * @param total la suma de lo contado, o `null` si todavía no se puede leer.
 * @param columns dos en una ventana ancha.
 */
@Composable
fun DenominationCounter(
    denominations: List<CashDenomination>,
    counts: Map<Long, String>,
    total: Money?,
    onCountChange: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 1,
) {
    val spacing = AppTheme.spacing
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        denominations.chunked(columns.coerceAtLeast(1)).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s16)) {
                group.forEach { denomination ->
                    DenominationRow(
                        denomination = denomination,
                        text = counts[denomination.id].orEmpty(),
                        onChange = { onCountChange(denomination.id, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - group.size) { Column(Modifier.weight(1f)) {} }
            }
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        FigureRow(
            stringResource(R.string.cash_total_counted),
            total,
            strong = true,
            sensitive = false,
        )
    }
}

/** Una denominación: su valor, las piezas y el subtotal. Con letra grande, el campo baja. */
@Composable
private fun DenominationRow(
    denomination: CashDenomination,
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = LocalAppFormats.current.money
    val value = rememberMoneyDisplay(denomination.value, money).text.text
    val pieces = text.trim().toLongOrNull()
    val subtotal = pieces?.let {
        try {
            Money(Math.multiplyExact(denomination.value.minor, it))
        } catch (_: ArithmeticException) {
            null
        }
    }
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        WholeNumberField(
            value = text,
            onValueChange = onChange,
            label = value,
            modifier = fieldModifier,
            imeAction = ImeAction.Next,
        )
    }
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
            field(Modifier.fillMaxWidth())
            MoneyText(subtotal ?: Money.ZERO, MoneySize.ROW)
        }
    } else {
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            field(Modifier.weight(1f))
            MoneyText(subtotal ?: Money.ZERO, MoneySize.ROW)
        }
    }
}

/** Desde qué tamaño de letra el subtotal baja bajo su campo. */
private const val STACKED_FONT_SCALE = 1.5f

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun DenominationCounterPreview() {
    val at = Instant.fromEpochMilliseconds(0)
    PreviewTheme {
        DenominationCounter(
            denominations = listOf(10_000L, 5_000L, 1_000L, 500L).mapIndexed { index, value ->
                CashDenomination(index + 1L, Money(value), true, index, at, at)
            },
            counts = mapOf(1L to "2", 2L to "3", 3L to "4", 4L to "1"),
            total = Money(39_500),
            onCountChange = { _, _ -> },
            modifier = Modifier.padding(AppTheme.spacing.s16),
        )
    }
}
