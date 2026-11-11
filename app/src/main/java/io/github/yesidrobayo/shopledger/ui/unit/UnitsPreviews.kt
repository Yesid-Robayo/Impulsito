package io.github.yesidrobayo.shopledger.ui.unit

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant

// Datos inventados para las vistas previas: un producto con el nombre más largo que se admite, una
// unidad de la persona y una archivada.

private val previewCount = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
private val previewKilo = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
private val previewBox = MeasureUnit(10L, "BOX", null, null, UnitKind.COUNT, false, null)
private val previewSack = MeasureUnit(30L, null, "Bulto", "blt", UnitKind.MASS, true, null)
private val previewOld =
    MeasureUnit(31L, null, "Fardo", null, UnitKind.OTHER, false, Instant.fromEpochMilliseconds(5L))

private fun previewProduct(id: Long, name: String, baseUnitId: Long) = Product(
    id = id,
    details =
        ProductDetails(
            name, name.lowercase(), null, null, baseUnitId, ProductStockMode.TRACKED, false,
            Money.ZERO, QuantityMilli.ZERO, null, null, null, null, null, null, null, null, null,
            null, null, null,
        ),
    salePrice = Money(5_000L),
    initialCost = Money.ZERO,
    averageCost = Money(3_800L),
    stockValue = Money(91_200L),
    onHand = QuantityMilli(24_000L),
    replacementCost = Money.ZERO,
    replacementCostAt = null,
    priceReviewSince = null,
    archivedAt = null,
    createdAt = Instant.fromEpochMilliseconds(0L),
    updatedAt = Instant.fromEpochMilliseconds(0L),
)

private val previewRice = previewProduct(7L, "Arroz 1 kg", previewCount.id)
private val previewCoffee =
    previewProduct(
        8L,
        "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos",
        previewKilo.id,
    )

private val previewState =
    UnitsUiState(
        loading = false,
        active = listOf(previewCount, previewKilo, previewBox, previewSack),
        archived = listOf(previewOld),
        inUse = setOf(previewCount.id, previewKilo.id),
        conversions =
            listOf(
                ConversionEntry(
                    ProductConversion(
                        1L,
                        previewRice.id,
                        ConversionDetails(previewBox.id, 24_000L, null, null, false, true),
                    ),
                    previewRice,
                ),
                ConversionEntry(
                    ProductConversion(
                        2L,
                        previewCoffee.id,
                        ConversionDetails(previewSack.id, 50_000L, null, null, true, true),
                    ),
                    previewCoffee,
                ),
            ),
        products = listOf(previewRice, previewCoffee),
    )

@ThemePreviews
@Composable
private fun UnitsListPreview() {
    PreviewTheme { UnitsScreen(previewState, onAction = {}) }
}

@ThemePreviews
@Composable
private fun ConversionsListPreview() {
    PreviewTheme { UnitsScreen(previewState.copy(tab = UnitsTab.CONVERSIONS), onAction = {}) }
}

@ThemePreviews
@Composable
private fun ConversionsEmptyPreview() {
    PreviewTheme {
        UnitsScreen(
            previewState.copy(
                tab = UnitsTab.CONVERSIONS,
                conversions = emptyList(),
            ),
            onAction = {
            },
        )
    }
}

@ThemePreviews
@Composable
private fun ConversionSheetPreview() {
    PreviewTheme(discreet = true) {
        UnitsScreen(
            previewState.copy(tab = UnitsTab.CONVERSIONS),
            onAction = {},
            conversionEditor =
                ConversionEditor(
                    editingId = 1L,
                    productId = previewRice.id,
                    productLocked = true,
                    unitId = previewBox.id,
                    factor = "24",
                    originalFactorMilli = 24_000L,
                ),
        )
    }
}

@ThemePreviews
@Composable
private fun UnitSheetPreview() {
    PreviewTheme {
        UnitsScreen(
            previewState,
            onAction = {},
            unitEditor =
                UnitEditor(
                    editingId = null,
                    isInitial = false,
                    name = "Bulto",
                    symbol = "blt",
                    kind = UnitKind.MASS,
                    allowsFraction = true,
                    nameProblem = UnitNameProblem.DUPLICATE,
                ),
        )
    }
}
