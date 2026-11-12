package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.currency.MoneyDisplay
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** El tamaño de un monto: cada uno es un estilo de `AppTheme.amounts`, con cifras tabulares. */
enum class MoneySize {
    /** La cifra del héroe. Se achica sola si no cabe. */
    HERO,

    /** El stock de un producto o el total de un período. Se achica sola si no cabe. */
    LARGE,

    /** El total de la venta. */
    TITLE,

    /** El valor de una tarjeta de cifra. */
    TILE,

    /** El monto principal de una fila. */
    ROW,

    /** La segunda línea numérica de una fila. */
    SMALL,

    /** Ejes y variaciones. */
    CAPTION,
}

/**
 * La tinta de un monto. Nunca el color de marca: una cifra en la marca parecería un botón y, en una
 * paleta rojiza, una pérdida.
 */
enum class MoneyTone {
    /** La tinta del papel. */
    DEFAULT,

    /** Secundario: una cifra de apoyo, una sin valor o una anulada. */
    MUTED,

    /** Una ganancia o una variación favorable. Una venta no es verde. */
    PROFIT,

    /** Una pérdida o una variación desfavorable. */
    LOSS,

    /** La cifra fuerte sobre el héroe. */
    ON_HERO,

    /** La cifra secundaria sobre el héroe, en el acento. Solo existe ahí. */
    HERO_ACCENT,
}

/**
 * Un monto de dinero, en el formato del negocio: símbolo, separadores, decimales y signo salen de
 * `MoneyFormatter`, y nunca se suponen.
 *
 * Una línea, sin elipsis y sin recorte: un monto cortado es un monto falso. `HERO` y `LARGE` se
 * achican por pasos hasta 24 sp antes de no caber, porque 13 cifras no entran a 40 sp en un teléfono
 * angosto.
 *
 * @param amount el monto, o `null` si no hay valor: se dibuja una raya y TalkBack dice «Sin dato».
 * @param voided si el documento está anulado: tachado, apagado y con la etiqueta «Anulada» al lado,
 *   para que el estado no dependa del tachado.
 * @param sensitive si es un costo, una ganancia, un saldo o un total de período: con el modo discreto
 *   encendido se oculta. El precio de lo que se atiende nunca es sensible.
 * @param loading si la cifra aún no llega: un esqueleto del ancho de siete cifras.
 * @param voidedLabel la etiqueta de un documento anulado, y [voidedDescription] su lectura en
 *   TalkBack, cuando el documento pide otra concordancia, como un gasto: «Anulado» y no «Anulada».
 */
@Composable
fun MoneyText(
    amount: Money?,
    size: MoneySize,
    modifier: Modifier = Modifier,
    tone: MoneyTone = MoneyTone.DEFAULT,
    voided: Boolean = false,
    sensitive: Boolean = false,
    loading: Boolean = false,
    @StringRes voidedLabel: Int = R.string.status_voided,
    @StringRes voidedDescription: Int = R.string.a11y_voided_amount,
) {
    val style = moneyStyle(size)
    val voidedText = VoidedText(voidedLabel, voidedDescription).takeIf { voided }
    when {
        loading -> MoneySkeleton(style, modifier)

        sensitive -> DiscreetValue(modifier, style, mutedColor(tone)) {
            MoneyValue(amount, size, style, tone, voidedText, Modifier)
        }

        else -> MoneyValue(amount, size, style, tone, voidedText, modifier)
    }
}

/** Los textos de un monto anulado: la etiqueta y su lectura en TalkBack. */
private class VoidedText(@StringRes val label: Int, @StringRes val description: Int)

@Composable
private fun MoneyValue(
    amount: Money?,
    size: MoneySize,
    style: TextStyle,
    tone: MoneyTone,
    voided: VoidedText?,
    modifier: Modifier,
) {
    if (amount == null) {
        val noValue = stringResource(R.string.a11y_no_value)
        Text(
            text = NO_VALUE_TEXT,
            style = style,
            color = mutedColor(tone),
            maxLines = 1,
            modifier = modifier.clearAndSetSemantics { contentDescription = noValue },
        )
        return
    }
    val display = rememberMoneyDisplay(amount, LocalAppFormats.current.money)
    val autoSize =
        when (size) {
            MoneySize.HERO, MoneySize.LARGE ->
                TextAutoSize.StepBased(minFontSize = MIN_AUTO_SIZE, maxFontSize = style.fontSize)

            else -> null
        }
    if (voided == null) {
        MoneyAmountText(display, style, modifier, moneyColor(tone), autoSize)
        return
    }
    val voidedDisplay =
        MoneyDisplay(
            text = display.text,
            description = stringResource(voided.description, display.description),
        )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        // La etiqueta ya va en la lectura del monto: TalkBack no la repite.
        StatusTag(
            text = stringResource(voided.label),
            modifier = Modifier.clearAndSetSemantics {},
        )
        MoneyAmountText(
            display = voidedDisplay,
            style = style.copy(textDecoration = TextDecoration.LineThrough),
            color = mutedColor(tone),
            autoSize = autoSize,
        )
    }
}

/** Un bloque de esqueleto del alto de la cifra y del ancho de siete cifras tabulares. */
@Composable
private fun MoneySkeleton(style: TextStyle, modifier: Modifier) {
    val loading = stringResource(R.string.a11y_loading)
    Box(modifier.semantics { contentDescription = loading }) {
        // Siete cifras invisibles dan el tamaño exacto en cualquier estilo y escala de letra.
        Text(
            text = SKELETON_DIGITS,
            style = style,
            maxLines = 1,
            modifier = Modifier.alpha(0f).clearAndSetSemantics {},
        )
        SkeletonBlock(Modifier.matchParentSize())
    }
}

/** El estilo de cada tamaño. */
@Composable
fun moneyStyle(size: MoneySize): TextStyle {
    val amounts = AppTheme.amounts
    return when (size) {
        MoneySize.HERO -> amounts.hero
        MoneySize.LARGE -> amounts.large
        MoneySize.TITLE -> amounts.title
        MoneySize.TILE -> amounts.tile
        MoneySize.ROW -> amounts.row
        MoneySize.SMALL -> amounts.small
        MoneySize.CAPTION -> amounts.caption
    }
}

/** La tinta de cada tono, de la paleta vigente. */
@Composable
fun moneyColor(tone: MoneyTone): Color = when (tone) {
    MoneyTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
    MoneyTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    MoneyTone.PROFIT -> AppTheme.colors.profit
    MoneyTone.LOSS -> AppTheme.colors.loss
    MoneyTone.ON_HERO -> AppTheme.colors.onHero
    MoneyTone.HERO_ACCENT -> AppTheme.colors.heroAccent
}

/**
 * El color apagado de un monto anulado, oculto o sin valor. Sobre el héroe es el apagado del héroe:
 * el de la superficie casi no se ve sobre el bloque de color.
 */
@Composable
internal fun mutedColor(tone: MoneyTone): Color = when (tone) {
    MoneyTone.ON_HERO, MoneyTone.HERO_ACCENT -> AppTheme.colors.onHeroVariant
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** El tamaño mínimo al que se achica una cifra grande: por debajo ya no es una cifra destacada. */
private val MIN_AUTO_SIZE = 24.sp

/** Siete cifras: el ancho del esqueleto de un monto. */
private const val SKELETON_DIGITS = "0000000"

/** Una muestra de cada estado, para las vistas previas y las capturas. */
@Immutable
private data class MoneySample(
    val amount: Money?,
    val size: MoneySize,
    val tone: MoneyTone = MoneyTone.DEFAULT,
)

@ThemePreviews
@Composable
private fun MoneyTextPreview() {
    PreviewTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            listOf(
                MoneySample(PreviewSamples.LONG_AMOUNT, MoneySize.HERO),
                MoneySample(PreviewSamples.MONTH_SALES, MoneySize.LARGE),
                MoneySample(PreviewSamples.SECOND_PRICE, MoneySize.TITLE),
                MoneySample(PreviewSamples.NEGATIVE_AMOUNT, MoneySize.TILE, MoneyTone.LOSS),
                MoneySample(PreviewSamples.PRICE, MoneySize.ROW),
                MoneySample(Money.ZERO, MoneySize.SMALL, MoneyTone.MUTED),
                MoneySample(null, MoneySize.ROW),
            ).forEach { MoneyText(it.amount, it.size, tone = it.tone) }
            MoneyText(PreviewSamples.SECOND_PRICE, MoneySize.ROW, voided = true)
            MoneyText(PreviewSamples.PRICE, MoneySize.ROW, loading = true)
        }
    }
}

@ThemePreviews
@Composable
private fun MoneyTextDiscreetPreview() {
    PreviewTheme(discreet = true) {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            MoneyText(PreviewSamples.PRICE, MoneySize.ROW)
            MoneyText(PreviewSamples.MONTH_SALES, MoneySize.TILE, sensitive = true)
        }
    }
}
