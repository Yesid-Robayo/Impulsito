package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Tarjetas inventadas para las vistas previas y las pruebas de `Decir o escribir`: una venta
 * completa, una compra con un producto por elegir, un gasto y una línea con un nombre de 80
 * caracteres y un monto largo. Ningún dato es real.
 */
internal object PhraseSamples {
    val unit =
        CardUnit(id = 1L, name = "Unidad", symbol = null, systemKey = "UNIT", kind = UnitKind.COUNT)
    val kilogram =
        CardUnit(
            id = 2L,
            name = "Kilogramo",
            symbol = null,
            systemKey = "KILOGRAM",
            kind = UnitKind.MASS,
        )
    private val cash = CardOption(1L, "Efectivo")
    private val transfer = CardOption(2L, "Transferencia")

    val sale =
        PhraseCard(
            intent = PhraseIntent.SALE,
            lines =
                listOf(
                    CardLine(
                        index = 0,
                        product = CardProduct.Chosen(10L, "Pan"),
                        quantity = QuantityMilli.ofUnits(3),
                        unit = unit,
                        unitPrice = Money(500L),
                        amount = Money(1_500L),
                    ),
                    CardLine(
                        index = 1,
                        product = CardProduct.Chosen(11L, "Gaseosa"),
                        quantity = QuantityMilli.ofUnits(2),
                        unit = unit,
                        unitPrice = null,
                        amount = null,
                    ),
                ),
            payment = CardChoice(selectedId = cash.id, options = listOf(cash, transfer)),
            party =
                CardParty(supplier = false, chosen = null, candidates = emptyList(), spoken = null),
        )

    /** Una compra con "harina" sin decir cuál, y sin costo: incompleta. */
    val purchaseToChoose =
        PhraseCard(
            intent = PhraseIntent.PURCHASE,
            lines =
                listOf(
                    CardLine(
                        index = 0,
                        product =
                            CardProduct.Missing(
                                candidates =
                                    listOf(
                                        CardOption(20L, "Harina de trigo"),
                                        CardOption(21L, "Harina de maíz"),
                                    ),
                                spoken = null,
                                canCreate = true,
                            ),
                        quantity = QuantityMilli.ofUnits(1),
                        unit = kilogram,
                        unitPrice = null,
                        amount = null,
                    ),
                ),
            payment = CardChoice(selectedId = null, options = listOf(cash, transfer)),
            party =
                CardParty(supplier = true, chosen = null, candidates = emptyList(), spoken = null),
            sensitive = true,
            gaps = setOf(DraftGap.PRODUCT, DraftGap.COST),
        )

    val expense =
        PhraseCard(
            intent = PhraseIntent.EXPENSE,
            category =
                CardChoice(
                    selectedId = 30L,
                    options = listOf(CardOption(30L, "Servicios"), CardOption(31L, "Transporte")),
                ),
            payment = CardChoice(selectedId = cash.id, options = listOf(cash, transfer)),
            total = Money(80_000L),
        )

    /** Un nombre de 80 caracteres. */
    const val LONG_NAME =
        "Torta de chocolate con nueces, crema y frutos rojos para veinte personas, grande"

    /** Un nombre de 80 caracteres y un monto de doce cifras, para ver que nada se corta. */
    val long =
        PhraseCard(
            intent = PhraseIntent.SALE,
            lines =
                listOf(
                    CardLine(
                        index = 0,
                        product =
                            CardProduct.Chosen(12L, LONG_NAME),
                        quantity = QuantityMilli(2_500L),
                        unit = kilogram,
                        unitPrice = Money(399_999_999_960L),
                        amount = Money(999_999_999_900L),
                    ),
                ),
            payment = CardChoice(selectedId = cash.id, options = listOf(cash, transfer)),
            total = Money(999_999_999_900L),
        )
}

@ThemePreviews
@Composable
private fun PhraseEmptyPreview() {
    PreviewTheme { PhraseScreen(PhraseUiState(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun PhraseNotUnderstoodPreview() {
    PreviewTheme {
        PhraseScreen(
            PhraseUiState(text = "eso de ayer", stage = PhraseStage.NotUnderstood),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PhraseSalePreview() {
    PreviewTheme {
        PhraseScreen(
            PhraseUiState(stage = PhraseStage.Card(PhraseSamples.sale), voiceReady = true),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PhraseIncompletePreview() {
    PreviewTheme(discreet = true) {
        PhraseScreen(
            PhraseUiState(
                stage =
                    PhraseStage.Card(
                        PhraseSamples.purchaseToChoose,
                        failure = PhraseFailure.Incomplete,
                    ),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PhraseLongPreview() {
    PreviewTheme { PhraseScreen(PhraseUiState(stage = PhraseStage.Card(PhraseSamples.long)), {}) }
}
