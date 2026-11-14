package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCatalog
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportChoice
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText
import io.github.yesidrobayo.shopledger.domain.rule.catalog.SupplierFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.SupplierFormRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerFormRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser

/**
 * Cómo se leen los números de un archivo para importar.
 *
 * Un CSV trae los números como los escribió la hoja de cálculo de la persona: con [decimal], el
 * separador decimal detectado o elegido, y quizá con el otro separador como miles, en grupos de
 * tres. Un `.xlsx` ([spreadsheet]) los trae siempre con punto decimal y sin miles, sea cual sea la
 * región de quien lo hizo.
 */
data class ImportNumberFormat(
    val decimal: DecimalSeparator,
    /** Los decimales de la moneda del negocio. */
    val currencyDecimals: Int,
    /** Si el archivo es una hoja de cálculo y no un CSV. */
    val spreadsheet: Boolean,
) {
    /** Los símbolos con que se lee cada número del archivo. */
    val symbols: NumberSymbols
        get() {
            val decimalChar = if (spreadsheet) DecimalSeparator.POINT.char else decimal.char
            val grouping = when {
                spreadsheet -> null
                decimal == DecimalSeparator.COMMA -> DigitGrouping('.', GROUP, GROUP)
                else -> DigitGrouping(',', GROUP, GROUP)
            }
            return NumberSymbols(
                decimalSeparator = decimalChar,
                minusSign = NumberSymbols.HYPHEN_MINUS,
                grouping = grouping,
            )
        }

    private companion object {
        const val GROUP = 3
    }
}

/**
 * Lee cada fila de un archivo ya relacionado con sus datos, con las mismas reglas de largo, rango
 * y tipo de una captura a mano, y arma la vista previa: qué se crea, qué ya existe y qué fila falla
 * y por qué. No escribe nada.
 *
 * - **Una fila con error no se importa, y el resto sigue.** Cada celda mala da su motivo, todos a
 *   la vez, para que la persona corrija la fila de una sola pasada.
 * - **Nunca se redondea.** Un monto con más decimales que la moneda, o una cantidad con más de
 *   tres, es un error de su fila: guardarlo recortado sería guardar un dato que nadie escribió.
 * - **Lo repetido en el archivo** se importa una vez: la primera fila manda y las siguientes con
 *   el mismo nombre, código o mes se marcan.
 * - **Lo que ya existe** se reconoce por el nombre normalizado, o por el mes en el índice. Un
 *   producto archivado se omite siempre, sin reactivarlo.
 * - **Una fila sin nada en las columnas relacionadas** no se cuenta: es una fila que solo trae
 *   columnas que no se importan.
 *
 * @param columns el dato de cada columna del archivo, por su posición, o `null` si no se importa.
 */
class ImportRowReader(
    private val target: ImportTarget,
    private val columns: List<String?>,
    private val numbers: ImportNumberFormat,
    private val catalog: ImportCatalog,
) {
    private val symbols = numbers.symbols
    private val index: Map<String, Int> =
        columns.withIndex().mapNotNull { (position, key) -> key?.let { it to position } }.toMap()

    /** La vista previa de las filas de [sheet]. */
    fun read(sheet: ImportSheet): ImportPreview {
        val issues = mutableListOf<ImportRowIssue>()
        val drafts = mutableListOf<ImportDraft>()
        val seenIdentities = mutableSetOf<String>()
        val seenBarcodes = mutableSetOf<String>()
        for (row in sheet.rows) {
            if (index.values.all { ImportCellText.text(row.cell(it)).isEmpty() }) continue
            val cells = RowCells(row)
            val draft = when (target) {
                ImportTarget.PRODUCTS -> product(cells)
                ImportTarget.CUSTOMERS -> customer(cells)
                ImportTarget.SUPPLIERS -> supplier(cells)
                ImportTarget.PRICE_INDEX -> priceIndex(cells)
            }
            draft?.let {
                if (!seenIdentities.add(it.identity)) {
                    cells.fail(identityField(), ImportIssue.DUPLICATE)
                }
            }
            (draft as? ImportDraft.Product)?.barcode?.let { code ->
                if (!seenBarcodes.add(code)) cells.fail(BARCODE, ImportIssue.DUPLICATE)
            }
            if (draft != null && cells.issues.isEmpty()) {
                drafts += draft
            } else {
                issues += cells.issues
            }
        }
        return preview(drafts, issues)
    }

    private fun identityField(): String = if (target == ImportTarget.PRICE_INDEX) MONTH else NAME

    private fun preview(drafts: List<ImportDraft>, issues: List<ImportRowIssue>): ImportPreview {
        val byState = drafts.groupBy { catalog.existing[it.identity] }
        val products = drafts.filterIsInstance<ImportDraft.Product>()
        val ready = byState[null].orEmpty()
        return ImportPreview(
            target = target,
            ready = ready,
            existing = byState[false].orEmpty(),
            locked = byState[true].orEmpty(),
            issues = issues,
            newCategories = newNames(products.mapNotNull { it.category }, catalog.categories),
            newSuppliers = newNames(products.mapNotNull { it.supplier }, catalog.suppliers),
            stockWithoutCost = ready.filterIsInstance<ImportDraft.Product>().count {
                (it.onHand?.milli ?: 0L) > 0L && (it.cost?.minor ?: 0L) == 0L
            },
        )
    }

    private fun newNames(names: List<String>, existing: Set<String>): Int =
        names.map(TextNormalizer::normalize).filter { it !in existing }.distinct().size

    private fun product(cells: RowCells): ImportDraft.Product? {
        val name = cells.name(NAME, CatalogText.PRODUCT_NAME_MAX)
        val category = cells.optionalText(CATEGORY, CatalogText.CATEGORY_NAME_MAX)
        val supplier = cells.optionalText(SUPPLIER, SupplierFormRules.NAME_MAX)
        val unit = cells.unit()
        val allowsFraction = unit?.allowsFraction ?: catalog.defaultUnitAllowsFraction
        val stockMode = cells.stockMode()
        val barcode = cells.optionalText(BARCODE, CatalogText.BARCODE_MAX)
        val salePrice = cells.amount(SALE_PRICE)
        val cost = cells.amount(AVERAGE_COST) ?: cells.amount(INITIAL_COST)
        val onHand = cells.quantity(ON_HAND, Limit.LINE_QUANTITY_MILLI, allowsFraction)
        val minStock = cells.quantity(MIN_STOCK, Limit.ON_HAND_MILLI, allowsFraction)
        if (stockMode == ProductStockMode.UNTRACKED) {
            // Sin libro no hay stock que contar ni que vigilar.
            if ((onHand?.milli ?: 0L) != 0L) cells.fail(ON_HAND, ImportIssue.QUANTITY)
            if ((minStock?.milli ?: 0L) != 0L) cells.fail(MIN_STOCK, ImportIssue.QUANTITY)
        }
        if (barcode != null) {
            val owner = catalog.barcodes[barcode]
            if (owner != null && name != null && owner != name.normalizedName) {
                cells.fail(BARCODE, ImportIssue.BARCODE_USED)
            }
        }
        name ?: return null
        return ImportDraft.Product(
            row = cells.row.number,
            name = name.name,
            normalizedName = name.normalizedName,
            category = category,
            supplier = supplier,
            unitId = unit?.id,
            stockMode = stockMode,
            barcode = barcode,
            salePrice = salePrice,
            cost = cost,
            onHand = onHand,
            minStock = minStock,
        )
    }

    private fun customer(cells: RowCells): ImportDraft.Contact? {
        val form = CustomerForm(
            name = cells.text(NAME),
            phone = cells.text(PHONE),
            email = cells.text(EMAIL),
            notes = cells.text(NOTES),
        )
        val priceListId = cells.priceList()
        return when (val check = CustomerFormRules.check(form)) {
            is CustomerFormCheck.Invalid -> {
                check.issues.forEach { cells.fail(customerField(it), customerIssue(it)) }
                null
            }

            is CustomerFormCheck.Valid -> {
                val contact = check.details.contact
                ImportDraft.Contact(
                    row = cells.row.number,
                    name = contact.name,
                    normalizedName = contact.normalizedName,
                    phone = contact.phone,
                    email = contact.email,
                    notes = contact.notes,
                    priceListId = priceListId,
                    paymentTermsDays = null,
                )
            }
        }
    }

    private fun supplier(cells: RowCells): ImportDraft.Contact? {
        val days = cells.days()
        val form = SupplierForm(
            name = cells.text(NAME),
            phone = cells.text(PHONE),
            email = cells.text(EMAIL),
            notes = cells.text(NOTES),
            paymentTermsDays = days,
        )
        return when (val check = SupplierFormRules.check(form)) {
            is SupplierFormCheck.Invalid -> {
                check.issues.forEach { cells.fail(supplierField(it), supplierIssue(it)) }
                null
            }

            is SupplierFormCheck.Valid -> {
                val details = check.details
                ImportDraft.Contact(
                    row = cells.row.number,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    phone = details.phone,
                    email = details.email,
                    notes = details.notes,
                    priceListId = null,
                    paymentTermsDays = details.paymentTermsDays,
                )
            }
        }
    }

    private fun priceIndex(cells: RowCells): ImportDraft.PriceIndex? {
        val monthText = cells.text(MONTH)
        val month = ImportCellText.month(monthText, numbers.spreadsheet)
        when {
            monthText.isEmpty() -> cells.fail(MONTH, ImportIssue.MISSING)
            month == null -> cells.fail(MONTH, ImportIssue.MONTH)
        }
        val change = cells.percent()
        return if (month != null && change != null) {
            ImportDraft.PriceIndex(cells.row.number, month, change)
        } else {
            null
        }
    }

    /** Las celdas de una fila, leídas por la clave de su dato, con los problemas que van dando. */
    private inner class RowCells(val row: ImportRow) {
        val issues = mutableListOf<ImportRowIssue>()

        fun fail(field: String, issue: ImportIssue) {
            issues += ImportRowIssue(row.number, field, issue)
        }

        /** El texto limpio de la celda de [field], o vacío si su columna no se importa. */
        fun text(field: String): String =
            index[field]?.let { ImportCellText.text(row.cell(it)) }.orEmpty()

        /** El texto de un número: en una hoja de cálculo, sin exponente ni arrastre binario. */
        private fun number(field: String): String {
            val text = text(field)
            return if (numbers.spreadsheet) ImportCellText.spreadsheetNumber(text) else text
        }

        fun name(field: String, maxLength: Int): CatalogName.Valid? =
            when (val name = CatalogText.name(text(field), maxLength)) {
                CatalogName.Empty -> null.also { fail(field, ImportIssue.MISSING) }
                CatalogName.TooLong -> null.also { fail(field, ImportIssue.LENGTH) }
                is CatalogName.Valid -> name
            }

        fun optionalText(field: String, maxLength: Int): String? {
            val text = text(field).ifEmpty { return null }
            if (CatalogText.length(text) > maxLength) fail(field, ImportIssue.LENGTH)
            return text
        }

        fun unit(): ImportChoice? {
            val text = text(BASE_UNIT).ifEmpty { return null }
            val normalized = TextNormalizer.normalize(text)
            return catalog.units.firstOrNull { normalized in it.names }
                ?: null.also { fail(BASE_UNIT, ImportIssue.UNIT_UNKNOWN) }
        }

        fun priceList(): Long? {
            val text = text(PRICE_LIST).ifEmpty { return null }
            val normalized = TextNormalizer.normalize(text)
            return catalog.priceLists.firstOrNull { normalized in it.names }?.id
                ?: null.also { fail(PRICE_LIST, ImportIssue.INVALID_VALUE) }
        }

        fun stockMode(): ProductStockMode? {
            val text = text(STOCK_MODE).ifEmpty { return null }
            // Un producto hecho de componentes se arma en su pantalla: un archivo no trae sus
            // componentes, y sin ellos no tendría costo ni stock.
            return IMPORTABLE_MODES.firstOrNull { it.name.equals(text, ignoreCase = true) }
                ?: null.also { fail(STOCK_MODE, ImportIssue.INVALID_VALUE) }
        }

        fun amount(field: String): Money? {
            val input = NumberInputParser.money(
                number(field),
                symbols,
                numbers.currencyDecimals,
                Limit.UNIT_MONEY,
            )
            return when (input) {
                is NumberInput.Value ->
                    input.value.takeIf { it.minor >= 0L }
                        ?: null.also { fail(field, ImportIssue.AMOUNT) }

                NumberInput.Blank -> null

                NumberInput.Malformed -> null.also { fail(field, ImportIssue.AMOUNT) }

                is NumberInput.TooManyDecimals -> null.also { fail(field, ImportIssue.DECIMALS) }

                is NumberInput.TooLarge -> null.also { fail(field, ImportIssue.LIMIT) }
            }
        }

        fun quantity(field: String, limit: Limit, allowsFraction: Boolean): QuantityMilli? =
            when (val input = NumberInputParser.quantity(number(field), symbols, limit)) {
                is NumberInput.Value -> {
                    val value = input.value
                    when {
                        value.milli < 0L -> null.also { fail(field, ImportIssue.QUANTITY) }

                        !allowsFraction && !value.isWholeUnits ->
                            null.also { fail(field, ImportIssue.UNIT_NO_FRACTION) }

                        else -> value
                    }
                }

                NumberInput.Blank -> null

                NumberInput.Malformed -> null.also { fail(field, ImportIssue.QUANTITY) }

                is NumberInput.TooManyDecimals -> null.also { fail(field, ImportIssue.DECIMALS) }

                is NumberInput.TooLarge -> null.also { fail(field, ImportIssue.LIMIT) }
            }

        fun percent(): BasisPoints? = when (
            val input = NumberInputParser.percent(number(CHANGE), symbols, MAX_INDEX_CHANGE)
        ) {
            is NumberInput.Value ->
                input.value.takeIf { it.bp >= MIN_INDEX_CHANGE.bp }
                    ?: null.also { fail(CHANGE, ImportIssue.LIMIT) }

            NumberInput.Blank -> null.also { fail(CHANGE, ImportIssue.MISSING) }

            NumberInput.Malformed -> null.also { fail(CHANGE, ImportIssue.INVALID_VALUE) }

            is NumberInput.TooManyDecimals -> null.also { fail(CHANGE, ImportIssue.DECIMALS) }

            is NumberInput.TooLarge -> null.also { fail(CHANGE, ImportIssue.LIMIT) }
        }

        /** Los días de plazo: un entero sin signo. Vacío es sin plazo. */
        fun days(): Int? {
            val digits = number(PAYMENT_TERMS).ifEmpty { return null }
            return when {
                !digits.all { it in '0'..'9' } ->
                    null.also { fail(PAYMENT_TERMS, ImportIssue.INVALID_VALUE) }

                digits.length > MAX_DAY_DIGITS ->
                    null.also { fail(PAYMENT_TERMS, ImportIssue.LIMIT) }

                else -> digits.toInt()
            }
        }
    }

    private fun customerField(issue: CustomerFormIssue): String = when (issue) {
        CustomerFormIssue.NAME_EMPTY, CustomerFormIssue.NAME_TOO_LONG -> NAME
        CustomerFormIssue.PHONE_TOO_LONG, CustomerFormIssue.PHONE_FORMAT -> PHONE
        CustomerFormIssue.EMAIL_TOO_LONG, CustomerFormIssue.EMAIL_FORMAT -> EMAIL
        CustomerFormIssue.NOTES_TOO_LONG -> NOTES
    }

    private fun customerIssue(issue: CustomerFormIssue): ImportIssue = when (issue) {
        CustomerFormIssue.NAME_EMPTY -> ImportIssue.MISSING

        CustomerFormIssue.NAME_TOO_LONG,
        CustomerFormIssue.PHONE_TOO_LONG,
        CustomerFormIssue.EMAIL_TOO_LONG,
        CustomerFormIssue.NOTES_TOO_LONG,
        -> ImportIssue.LENGTH

        CustomerFormIssue.PHONE_FORMAT -> ImportIssue.PHONE

        CustomerFormIssue.EMAIL_FORMAT -> ImportIssue.EMAIL
    }

    private fun supplierField(issue: SupplierFormIssue): String = when (issue) {
        SupplierFormIssue.NAME_EMPTY, SupplierFormIssue.NAME_TOO_LONG -> NAME
        SupplierFormIssue.PHONE_TOO_LONG, SupplierFormIssue.PHONE_FORMAT -> PHONE
        SupplierFormIssue.EMAIL_TOO_LONG, SupplierFormIssue.EMAIL_FORMAT -> EMAIL
        SupplierFormIssue.NOTES_TOO_LONG -> NOTES
        SupplierFormIssue.PAYMENT_TERMS_OUT_OF_RANGE -> PAYMENT_TERMS
    }

    private fun supplierIssue(issue: SupplierFormIssue): ImportIssue = when (issue) {
        SupplierFormIssue.NAME_EMPTY -> ImportIssue.MISSING

        SupplierFormIssue.NAME_TOO_LONG,
        SupplierFormIssue.PHONE_TOO_LONG,
        SupplierFormIssue.EMAIL_TOO_LONG,
        SupplierFormIssue.NOTES_TOO_LONG,
        -> ImportIssue.LENGTH

        SupplierFormIssue.PHONE_FORMAT -> ImportIssue.PHONE

        SupplierFormIssue.EMAIL_FORMAT -> ImportIssue.EMAIL

        SupplierFormIssue.PAYMENT_TERMS_OUT_OF_RANGE -> ImportIssue.LIMIT
    }

    private companion object {
        const val NAME = "name"
        const val CATEGORY = "category"
        const val SUPPLIER = "default_supplier"
        const val BASE_UNIT = "base_unit"
        const val STOCK_MODE = "stock_mode"
        const val BARCODE = "barcode"
        const val SALE_PRICE = "sale_price"
        const val AVERAGE_COST = "average_cost"
        const val INITIAL_COST = "initial_cost"
        const val ON_HAND = "on_hand"
        const val MIN_STOCK = "min_stock"
        const val PHONE = "phone"
        const val EMAIL = "email"
        const val NOTES = "notes"
        const val PRICE_LIST = "price_list"
        const val PAYMENT_TERMS = "payment_terms_days"
        const val MONTH = "month"
        const val CHANGE = "change_percent"

        /** Más cifras que esto ya pasa cualquier plazo, y no cabría en un entero. */
        const val MAX_DAY_DIGITS = 6

        /** La variación de un mes del índice va de −100 % a +10.000 %, como la guarda la base. */
        val MIN_INDEX_CHANGE = BasisPoints(-10_000L)
        val MAX_INDEX_CHANGE = BasisPoints(1_000_000L)

        val IMPORTABLE_MODES = listOf(ProductStockMode.TRACKED, ProductStockMode.UNTRACKED)
    }
}
