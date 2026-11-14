package io.github.yesidrobayo.shopledger.ui.imports

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de importar datos, con datos inventados: cada paso, un nombre de 80 caracteres,
// un monto de trece cifras, una columna sin relacionar, filas con error y el archivo rechazado.

internal fun previewImportMap(): ImportUiState {
    val s = PreviewSamples
    return ImportUiState(
        step = ImportStep.MAP,
        fileName = "productos.csv",
        rowCount = 412,
        separator = CsvSeparator.SEMICOLON,
        decimal = DecimalSeparator.COMMA,
        formatExpanded = true,
        fields = ImportTarget.PRODUCTS.fields.map { it.key },
        columns = listOf(
            ImportColumnUi(0, "nombre", listOf(s.SHORT_NAME, s.LONG_NAME), "name", true),
            ImportColumnUi(1, "Precio venta", listOf("12.500", "4.200"), null, false),
            ImportColumnUi(2, "costo", listOf("3.800"), "average_cost", true),
            ImportColumnUi(3, "stock", listOf("24"), "on_hand", true),
        ),
    )
}

internal fun previewImportReview(): ImportUiState {
    val s = PreviewSamples
    val drafts = listOf(
        product(2, s.SHORT_NAME, Money(420_000L), Money(380_000L)),
        product(3, s.LONG_NAME, s.LONG_AMOUNT, null),
        product(4, s.SECOND_NAME, Money(1_250_000L), Money(900_000L)),
    )
    return previewImportMap().copy(
        step = ImportStep.REVIEW,
        preview = ImportPreview(
            target = ImportTarget.PRODUCTS,
            ready = drafts.take(2),
            existing = drafts.drop(2),
            locked = emptyList(),
            issues = listOf(
                ImportRowIssue(17, "sale_price", ImportIssue.AMOUNT),
                ImportRowIssue(203, "base_unit", ImportIssue.UNIT_UNKNOWN),
            ),
            newCategories = 2,
            stockWithoutCost = 3,
        ),
    )
}

private fun product(row: Int, name: String, price: Money, cost: Money?) = ImportDraft.Product(
    row = row,
    name = name,
    normalizedName = name.lowercase(),
    category = null,
    supplier = null,
    unitId = null,
    stockMode = null,
    barcode = null,
    salePrice = price,
    cost = cost,
    onHand = QuantityMilli(24_000L),
    minStock = null,
)

@ThemePreviews
@Composable
private fun ImportFilePreview() {
    PreviewTheme {
        ImportScreen(
            ImportUiState(
                currency = ImportCurrencyUi(code = "XTS", confirmed = true, locked = false),
                fileProblem = ImportFileProblem.Rejected(ImportRejection.TOO_LARGE),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ImportMapPreview() {
    PreviewTheme { ImportScreen(previewImportMap().copy(missing = listOf("name")), {}) }
}

@ThemePreviews
@Composable
private fun ImportReviewPreview() {
    PreviewTheme { ImportScreen(previewImportReview(), {}) }
}

@ThemePreviews
@Composable
private fun ImportNothingToApplyPreview() {
    val review = previewImportReview()
    val preview = review.preview?.copy(ready = emptyList(), existing = emptyList())
    PreviewTheme { ImportScreen(review.copy(preview = preview), {}) }
}

@ThemePreviews
@Composable
private fun ImportDonePreview() {
    PreviewTheme {
        ImportScreen(
            ImportUiState(step = ImportStep.DONE, done = ImportDoneUi(406, 18, 0, 6)),
            {},
        )
    }
}

@Preview(name = "Tableta", widthDp = 1024, heightDp = 768)
@Composable
private fun ImportTabletPreview() {
    PreviewTheme { ImportScreen(previewImportReview(), {}) }
}
