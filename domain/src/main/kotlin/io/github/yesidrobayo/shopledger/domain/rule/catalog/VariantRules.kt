package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/** Una variante lista para crear: su nombre, sus valores recortados y su código, o `null`. */
data class PlannedVariant(
    val name: String,
    val normalizedName: String,
    val values: VariantValues,
    val barcode: String?,
    val draft: VariantDraft,
)

/** Resultado de [VariantRules.check]. */
sealed interface VariantPlanCheck {
    /** Se pueden crear: los ejes recortados y cada variante en el orden en que llegó. */
    data class Valid(val axes: VariantAxes, val variants: List<PlannedVariant>) : VariantPlanCheck

    /**
     * No se pueden crear, por todos estos motivos a la vez: [axisIssues] de los ejes, y por el
     * índice de cada variante, [variantIssues] de su combinación y [formIssues] de sus montos,
     * cantidades y código.
     */
    data class Invalid(
        val axisIssues: Set<VariantIssue>,
        val variantIssues: Map<Int, Set<VariantIssue>>,
        val formIssues: Map<Int, Set<ProductFormIssue>>,
    ) : VariantPlanCheck
}

/**
 * Las reglas de las variantes de un producto.
 *
 * Cada variante es un producto con su propio libro, ligado a su producto principal, con uno o dos
 * ejes. Su nombre es el del principal más sus valores, separados por un punto medio, que no es de
 * ningún idioma: `Camiseta · M · Rojo`. Cada combinación de valores es única dentro del
 * principal, comparada sin mayúsculas ni tildes, y cuenta también la de una variante archivada: se
 * reactiva en vez de crearla otra vez, para no partir su historia en dos productos.
 */
object VariantRules {
    /** Largo máximo del nombre de un eje y de un valor, el de sus columnas en la base. */
    const val TEXT_MAX: Int = 40

    /** Lo que separa el nombre del principal de cada valor en el nombre de una variante. */
    const val NAME_SEPARATOR: String = " · "

    /** El nombre de la variante de [parentName] con [values] ya recortados. */
    fun nameOf(parentName: String, values: VariantValues): String =
        listOfNotNull(parentName, values.first, values.second).joinToString(NAME_SEPARATOR)

    /**
     * Revisa crear [drafts] como variantes de [parent] con [axes]. [existing] son las variantes
     * que [parent] ya tiene, archivadas o no, y [allowsFraction] es de su unidad base, que las
     * variantes heredan.
     */
    fun check(
        parent: Product,
        axes: VariantAxes,
        drafts: List<VariantDraft>,
        existing: List<Product>,
        allowsFraction: Boolean,
    ): VariantPlanCheck {
        val axisIssues = mutableSetOf<VariantIssue>()
        val first = text(axes.first, VariantIssue.AXIS_EMPTY, VariantIssue.AXIS_TOO_LONG)
        val second = axes.second?.let {
            text(it, VariantIssue.AXIS_EMPTY, VariantIssue.AXIS_TOO_LONG)
        }
        first.issue?.let { axisIssues += it }
        second?.issue?.let { axisIssues += it }
        if (drafts.isEmpty()) axisIssues += VariantIssue.NO_VARIANTS
        val cleanAxes = VariantAxes(first.text, second?.text)
        if (existing.isNotEmpty() && !sameAxes(cleanAxes, existing.first())) {
            axisIssues += VariantIssue.AXES_CHANGED
        }
        val taken = existing.mapTo(mutableSetOf()) {
            key(it.details.variantValue1.orEmpty(), it.details.variantValue2)
        }
        val barcodes = drafts.mapNotNull { CatalogText.barcode(it.barcode) }
            .groupingBy { it }
            .eachCount()
        val variantIssues = mutableMapOf<Int, Set<VariantIssue>>()
        val formIssues = mutableMapOf<Int, Set<ProductFormIssue>>()
        val planned = drafts.mapIndexedNotNull { index, draft ->
            val issues = mutableSetOf<VariantIssue>()
            val plan = plan(parent, draft, second != null, issues)
            if (plan != null && !taken.add(key(plan.values.first, plan.values.second))) {
                issues += VariantIssue.DUPLICATE_COMBINATION
            }
            val barcode = CatalogText.barcode(draft.barcode)
            if (barcode != null && barcodes.getValue(barcode) > 1) {
                issues += VariantIssue.BARCODE_REPEATED
            }
            val form = formIssues(parent, draft, allowsFraction)
            if (issues.isNotEmpty()) variantIssues[index] = issues
            if (form.isNotEmpty()) formIssues[index] = form
            plan
        }
        return if (axisIssues.isEmpty() && variantIssues.isEmpty() && formIssues.isEmpty()) {
            VariantPlanCheck.Valid(cleanAxes, planned)
        } else {
            VariantPlanCheck.Invalid(axisIssues, variantIssues, formIssues)
        }
    }

    /**
     * La variante de [draft] con su nombre, o `null` si un valor no sirve. Agrega a [issues] lo
     * que encuentre. [twoAxes] dice si la combinación debe llevar dos valores.
     */
    private fun plan(
        parent: Product,
        draft: VariantDraft,
        twoAxes: Boolean,
        issues: MutableSet<VariantIssue>,
    ): PlannedVariant? {
        if ((draft.values.second != null) != twoAxes) {
            issues += VariantIssue.VALUES_DO_NOT_MATCH_AXES
            return null
        }
        val first = text(draft.values.first, VariantIssue.VALUE_EMPTY, VariantIssue.VALUE_TOO_LONG)
        val second = draft.values.second?.let {
            text(it, VariantIssue.VALUE_EMPTY, VariantIssue.VALUE_TOO_LONG)
        }
        first.issue?.let { issues += it }
        second?.issue?.let { issues += it }
        if (issues.isNotEmpty()) return null
        val values = VariantValues(first.text, second?.text)
        val name = nameOf(parent.details.name, values)
        if (CatalogText.length(name) > CatalogText.PRODUCT_NAME_MAX) {
            issues += VariantIssue.NAME_TOO_LONG
            return null
        }
        return PlannedVariant(
            name = name,
            normalizedName = TextNormalizer.normalize(name),
            values = values,
            barcode = CatalogText.barcode(draft.barcode),
            draft = draft,
        )
    }

    /**
     * Los montos, cantidades y código de [draft], con las reglas de un producto suelto del mismo
     * modo de stock que [parent]. El nombre se revisa aparte, porque no lo escribió la persona.
     */
    private fun formIssues(
        parent: Product,
        draft: VariantDraft,
        allowsFraction: Boolean,
    ): Set<ProductFormIssue> {
        val form = ProductForm(
            name = parent.details.name,
            stockMode = parent.details.stockMode,
            salePrice = draft.salePrice,
            cost = draft.cost,
            minStock = draft.minStock,
            barcode = draft.barcode,
        )
        val check = ProductFormRules.check(form, draft.initialStock, allowsFraction)
        return when (check) {
            is ProductFormCheck.Valid -> emptySet()
            is ProductFormCheck.Invalid -> check.issues
        }
    }

    private fun sameAxes(axes: VariantAxes, variant: Product): Boolean =
        normalized(axes.first) == normalized(variant.details.variantAxis1.orEmpty()) &&
            normalizedOrNull(axes.second) == normalizedOrNull(variant.details.variantAxis2)

    private fun key(first: String, second: String?): Pair<String, String?> =
        normalized(first) to normalizedOrNull(second)

    private fun normalized(text: String): String = TextNormalizer.normalize(text.trim())

    private fun normalizedOrNull(text: String?): String? = text?.let(::normalized)

    /** [raw] recortado, con el motivo que impide usarlo, o `null` si sirve. */
    private fun text(raw: String, empty: VariantIssue, tooLong: VariantIssue): CheckedText {
        val trimmed = raw.trim()
        val issue = when {
            TextNormalizer.normalize(trimmed).isEmpty() -> empty
            CatalogText.length(trimmed) > TEXT_MAX -> tooLong
            else -> null
        }
        return CheckedText(trimmed, issue)
    }

    private data class CheckedText(val text: String, val issue: VariantIssue?)
}
