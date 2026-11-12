package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.PriceSuggestion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText

/** Por qué el margen objetivo escrito no sirve para sugerir un precio. */
enum class TargetMarginProblem {
    NOT_A_NUMBER,
    TOO_MANY_DECIMALS,

    /** Negativo o mayor que 99 %: con 100 % el precio tendría que ser infinito. */
    OUT_OF_RANGE,
}

/** Lo que dice la sugerencia de precio con el margen objetivo que se escribe. */
sealed interface SuggestionOutcome {
    /** [price] deja el margen pedido, y [margin] es el que deja de verdad, con paso e impuesto. */
    data class Suggested(val price: Money, val margin: Margin) : SuggestionOutcome

    /** Sin costo no hay sugerencia: primero se corrige el costo. */
    data object NoCost : SuggestionOutcome

    /** El margen está vacío: todavía no hay nada que sugerir, ni un error que mostrar. */
    data object Blank : SuggestionOutcome

    data class InvalidTarget(val problem: TargetMarginProblem) : SuggestionOutcome

    /** El precio que alcanza ese margen pasa el máximo por unidad. */
    data object AbovePriceLimit : SuggestionOutcome
}

/**
 * La sugerencia de precio mientras se escribe: el margen objetivo como texto y lo que sugiere. Lo
 * escrito no se guarda en el producto ni en el negocio.
 */
@Immutable
data class PriceSuggestionDraft(val targetText: String, val outcome: SuggestionOutcome) {
    /** El precio que se puede usar, o `null` si no hay uno. */
    val price: Money? get() = (outcome as? SuggestionOutcome.Suggested)?.price

    companion object {
        /**
         * La sugerencia al abrirla: con el margen objetivo de [pricing], escrito con los símbolos
         * de [symbols] como lo escribiría la persona.
         */
        fun open(pricing: ProductPricing, symbols: NumberSymbols): PriceSuggestionDraft {
            val text = plainNumberText(pricing.targetMargin.bp, PERCENT_DECIMALS, symbols)
            return PriceSuggestionDraft(text, read(text, pricing, symbols))
        }

        /** Lo que sugiere [targetText], el margen escrito, con lo que sabe [pricing]. */
        fun read(
            targetText: String,
            pricing: ProductPricing,
            symbols: NumberSymbols,
        ): SuggestionOutcome {
            if (!pricing.canSuggest) return SuggestionOutcome.NoCost
            val target = when (
                val typed =
                    NumberInputParser.percent(targetText, symbols, BasisPoints.MAX_TARGET_MARGIN)
            ) {
                is NumberInput.Value -> typed.value

                NumberInput.Blank -> return SuggestionOutcome.Blank

                NumberInput.Malformed ->
                    return SuggestionOutcome.InvalidTarget(TargetMarginProblem.NOT_A_NUMBER)

                is NumberInput.TooManyDecimals ->
                    return SuggestionOutcome.InvalidTarget(TargetMarginProblem.TOO_MANY_DECIMALS)

                is NumberInput.TooLarge ->
                    return SuggestionOutcome.InvalidTarget(TargetMarginProblem.OUT_OF_RANGE)
            }
            return when (val suggestion = pricing.suggest(target)) {
                is PriceSuggestion.Suggested ->
                    SuggestionOutcome.Suggested(
                        suggestion.price,
                        pricing.marginOf(suggestion.price),
                    )

                PriceSuggestion.NoCost -> SuggestionOutcome.NoCost

                PriceSuggestion.TargetMarginOutOfRange ->
                    SuggestionOutcome.InvalidTarget(TargetMarginProblem.OUT_OF_RANGE)

                PriceSuggestion.AbovePriceLimit -> SuggestionOutcome.AbovePriceLimit
            }
        }

        /** Los decimales de un porcentaje escrito: hasta centésimas, como 12,5 %. */
        private const val PERCENT_DECIMALS = 2
    }
}
