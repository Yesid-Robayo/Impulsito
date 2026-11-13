package io.github.yesidrobayo.shopledger.ui.pricing

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

@ThemePreviews
@Composable
private fun PriceListsPreview() {
    PreviewTheme { PriceListsScreen(PriceListSamples.lists, onAction = {}) }
}

@ThemePreviews
@Composable
private fun PriceListsEmptyPreview() {
    PreviewTheme { PriceListsScreen(PriceListSamples.empty, onAction = {}) }
}

@ThemePreviews
@Composable
private fun PriceListDetailPreview() {
    PreviewTheme(discreet = true) { PriceListsScreen(PriceListSamples.detail, onAction = {}) }
}

@ThemePreviews
@Composable
private fun PriceListSheetPreview() {
    PreviewTheme {
        PriceListsScreen(
            PriceListSamples.lists,
            onAction = {},
            listEditor =
                PriceListEditor(
                    editingId = PriceListSamples.SPECIAL_ID,
                    name = PriceListSamples.special.name,
                    kind = PriceListKind.RELATIVE,
                    baseId = PriceListSamples.WHOLESALE_ID,
                    adjustmentText = "-10",
                    bases = listOf(PriceListSamples.default, PriceListSamples.wholesale),
                    baseProblem = ListBaseProblem.CYCLE,
                ),
        )
    }
}

@ThemePreviews
@Composable
private fun ListPriceSheetPreview() {
    PreviewTheme {
        PriceListsScreen(
            PriceListSamples.detail.copy(selectedId = PriceListSamples.WHOLESALE_ID),
            onAction = {},
            priceEditor =
                ListPriceEditor(
                    listId = PriceListSamples.WHOLESALE_ID,
                    productId = 7L,
                    productName = PriceListSamples.rows.first().name,
                    priceText = "4500",
                    hasOwnPrice = true,
                    pricing = PriceListSamples.pricing,
                    typedPrice = Money(4_500L),
                ),
        )
    }
}
