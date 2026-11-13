package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode

/**
 * Valida lo que la persona escribió de un producto antes de guardarlo, y devuelve todos los motivos
 * a la vez, para que el formulario marque cada campo de una sola pasada.
 *
 * Los topes protegen las cuentas que vienen después de desbordar `Long`, no cifras de una moneda:
 * valen igual con cualquier moneda. Las cantidades siguen la unidad base del producto: una unidad
 * que se cuenta no admite decimales. La base tiene las mismas reglas en sus guardas, como última
 * defensa, pero un rechazo de la base no dice qué campo corregir.
 */
object ProductFormRules {
    /**
     * Revisa [form] con un stock inicial [initialStock], que vale cero al editar. [allowsFraction]
     * es de la unidad base que eligió la persona.
     */
    fun check(
        form: ProductForm,
        initialStock: QuantityMilli,
        allowsFraction: Boolean,
    ): ProductFormCheck {
        val issues = mutableSetOf<ProductFormIssue>()
        val name = CatalogText.name(form.name, CatalogText.PRODUCT_NAME_MAX)
        when (name) {
            CatalogName.Empty -> issues += ProductFormIssue.NAME_EMPTY
            CatalogName.TooLong -> issues += ProductFormIssue.NAME_TOO_LONG
            is CatalogName.Valid -> Unit
        }
        if (!isUnitMoney(form.salePrice)) issues += ProductFormIssue.SALE_PRICE_OUT_OF_RANGE
        costIssue(form)?.let { issues += it }
        replacementIssue(form)?.let { issues += it }
        form.referencePrice?.takeUnless(::isUnitMoney)?.let {
            issues += ProductFormIssue.REFERENCE_PRICE_OUT_OF_RANGE
        }
        if (StockModeRules.hasLedger(form.stockMode)) {
            quantityIssue(initialStock, allowsFraction, mustMove = false)?.let { issues += it }
            minStockIssue(form.minStock, allowsFraction)?.let { issues += it }
        } else if (initialStock != QuantityMilli.ZERO || form.minStock != QuantityMilli.ZERO) {
            // Sin libro no hay stock que contar ni que vigilar.
            issues += ProductFormIssue.STOCK_NOT_APPLICABLE
        }
        val barcode = CatalogText.barcode(form.barcode)
        if (barcode != null && CatalogText.length(barcode) > CatalogText.BARCODE_MAX) {
            issues += ProductFormIssue.BARCODE_TOO_LONG
        }
        return if (name !is CatalogName.Valid || issues.isNotEmpty()) {
            ProductFormCheck.Invalid(issues)
        } else {
            ProductFormCheck.Valid(name.name, name.normalizedName, barcode)
        }
    }

    /**
     * Lo que impide registrar un inventario inicial de [quantity] a [unitCost] por unidad entera.
     * Debe mover algo: un inventario inicial de cero no registra nada.
     */
    fun openingIssues(
        quantity: QuantityMilli,
        unitCost: Money,
        allowsFraction: Boolean,
    ): Set<ProductFormIssue> = setOfNotNull(
        quantityIssue(quantity, allowsFraction, mustMove = true),
        if (isUnitMoney(unitCost)) null else ProductFormIssue.COST_OUT_OF_RANGE,
    )

    /**
     * El costo de reposición escrito: dentro del tope de un costo por unidad, y solo en un producto
     * con stock propio. Cero o sin escribir siempre vale, porque es sin dato.
     */
    private fun replacementIssue(form: ProductForm): ProductFormIssue? {
        val replacement = form.replacementCost ?: return null
        return when {
            !isUnitMoney(replacement) -> ProductFormIssue.REPLACEMENT_COST_OUT_OF_RANGE

            replacement != Money.ZERO && form.stockMode != ProductStockMode.TRACKED ->
                ProductFormIssue.REPLACEMENT_COST_NOT_APPLICABLE

            else -> null
        }
    }

    private fun costIssue(form: ProductForm): ProductFormIssue? = when {
        !isUnitMoney(form.cost) -> ProductFormIssue.COST_OUT_OF_RANGE

        // El costo de un producto hecho de componentes es la suma de sus componentes.
        form.stockMode == ProductStockMode.COMPONENTS && form.cost != Money.ZERO ->
            ProductFormIssue.COST_NOT_APPLICABLE

        else -> null
    }

    private fun quantityIssue(
        quantity: QuantityMilli,
        allowsFraction: Boolean,
        mustMove: Boolean,
    ): ProductFormIssue? = when {
        quantity.milli < 0L || !Limit.LINE_QUANTITY_MILLI.allows(quantity.milli) ->
            ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE

        quantity == QuantityMilli.ZERO ->
            if (mustMove) ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE else null

        !allowsFraction && !quantity.isWholeUnits ->
            ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED

        else -> null
    }

    private fun minStockIssue(minStock: QuantityMilli, allowsFraction: Boolean): ProductFormIssue? =
        when {
            minStock.milli < 0L || !Limit.ON_HAND_MILLI.allows(minStock.milli) ->
                ProductFormIssue.MIN_STOCK_OUT_OF_RANGE

            !allowsFraction && !minStock.isWholeUnits ->
                ProductFormIssue.MIN_STOCK_FRACTION_NOT_ALLOWED

            else -> null
        }

    private fun isUnitMoney(amount: Money): Boolean =
        amount.minor >= 0L && Limit.UNIT_MONEY.allows(amount.minor)
}
