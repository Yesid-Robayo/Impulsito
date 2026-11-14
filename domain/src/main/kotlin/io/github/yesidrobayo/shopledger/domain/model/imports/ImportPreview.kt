package io.github.yesidrobayo.shopledger.domain.model.imports

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage

/**
 * Por qué una fila del archivo no se puede importar. Cada motivo es de una celda: la fila se lista
 * con su número, su columna y el motivo, y el resto del archivo sigue.
 */
enum class ImportIssue {
    /** Falta un dato obligatorio, como el nombre. */
    MISSING,

    /** El monto no es un número de la moneda, o es negativo: trae letras, el símbolo o signos. */
    AMOUNT,

    /** La cantidad no es un número, o es negativa. */
    QUANTITY,

    /** Trae más decimales que la moneda, la cantidad o el porcentaje: nunca se redondea. */
    DECIMALS,

    /** El texto pasa el largo del campo. */
    LENGTH,

    /** Otra fila del mismo archivo ya trae este nombre, código o mes. */
    DUPLICATE,

    /** El código de barras ya lo tiene otro producto vigente. */
    BARCODE_USED,

    /** La unidad no es ninguna de las del negocio, en ningún idioma. */
    UNIT_UNKNOWN,

    /** La unidad del producto no admite decimales y la cantidad los trae. */
    UNIT_NO_FRACTION,

    /** El número pasa el máximo que la app guarda. */
    LIMIT,

    /** El correo no tiene la forma de un correo. */
    EMAIL,

    /** El teléfono tiene algo que no es una cifra ni un signo de marcar. */
    PHONE,

    /** El mes no es `AAAA-MM`, ni una fecha de la hoja de cálculo. */
    MONTH,

    /**
     * El valor no es ninguno de los que admite la columna, como un modo de stock o una lista, o no
     * es un número en una columna de porcentaje o de días.
     */
    INVALID_VALUE,
}

/** El problema de una celda: la fila de la hoja, la clave de su dato y el motivo. */
data class ImportRowIssue(
    /** El número de la fila en la hoja, el mismo que ve la persona. */
    val row: Int,
    /** La clave estable del dato, como `sale_price`. */
    val field: String,
    val issue: ImportIssue,
)

/** Qué hacer con una fila que ya existe en el negocio. */
enum class ImportExistingPolicy {
    /** No se toca: se cuenta como omitida. Es lo que se ofrece primero. */
    SKIP,

    /**
     * Se cambian sus datos de catálogo y su precio. Nunca su stock ni su costo si ya tiene
     * movimientos.
     */
    UPDATE,
}

/**
 * Una fila válida del archivo, ya leída con las reglas de su campo y lista para escribir. Los
 * textos van recortados y sin el apóstrofo que protegía una fórmula o un número en la hoja. Un
 * dato que la fila no trae, porque su columna no se relacionó o vino vacía, es `null`: así
 * actualizar uno existente no borra lo que el archivo no dice.
 */
sealed interface ImportDraft {
    /** El número de la fila en la hoja. */
    val row: Int

    /** Cómo se reconoce en el negocio: el nombre normalizado, o el mes del índice. */
    val identity: String

    /** Un producto. */
    data class Product(
        override val row: Int,
        val name: String,
        val normalizedName: String,
        /** El nombre de la categoría, que se crea si no existe. */
        val category: String?,
        /** El nombre del proveedor habitual, que se crea si no existe. */
        val supplier: String?,
        /** La unidad base elegida, o `null` para la de conteo. */
        val unitId: Long?,
        val stockMode: ProductStockMode?,
        val barcode: String?,
        val salePrice: Money?,
        /** El costo por unidad entera: el costo promedio si viene, si no el inicial. */
        val cost: Money?,
        /** El stock inicial, solo para un producto nuevo. */
        val onHand: QuantityMilli?,
        val minStock: QuantityMilli?,
        /** Si se vende, es un insumo o las dos cosas; `null` deja el de siempre. */
        val usage: ProductUsage? = null,
    ) : ImportDraft {
        override val identity: String get() = normalizedName
    }

    /** Un cliente o un proveedor: los dos tienen nombre y forma de contacto. */
    data class Contact(
        override val row: Int,
        val name: String,
        val normalizedName: String,
        val phone: String?,
        val email: String?,
        val notes: String?,
        /** La lista de precios de un cliente, ya reconocida. */
        val priceListId: Long?,
        /** Los días de plazo de un proveedor. */
        val paymentTermsDays: Int?,
    ) : ImportDraft {
        override val identity: String get() = normalizedName
    }

    /** La variación de un mes del índice de precios. */
    data class PriceIndex(
        override val row: Int,
        /** `AAAA-MM`. */
        val month: String,
        val change: BasisPoints,
    ) : ImportDraft {
        override val identity: String get() = month
    }
}

/**
 * Lo que se va a importar, antes de escribir nada: las filas listas, las que ya existen y las que
 * tienen error, con lo que la persona debe saber antes de confirmar.
 */
data class ImportPreview(
    val target: ImportTarget,
    /** Las filas válidas que no existen en el negocio: se crean. */
    val ready: List<ImportDraft>,
    /** Las filas válidas que ya existen: se omiten o se actualizan, según elija la persona. */
    val existing: List<ImportDraft>,
    /**
     * Las filas que ya existen pero no se pueden actualizar nunca, como un producto archivado: se
     * omiten siempre, sin reactivarlo.
     */
    val locked: List<ImportDraft>,
    /** Los problemas de cada celda, por fila y en el orden de la hoja. */
    val issues: List<ImportRowIssue>,
    /** Las categorías que no existen y se crearán. */
    val newCategories: Int = 0,
    /** Los proveedores que no existen y se crearán como proveedor habitual. */
    val newSuppliers: Int = 0,
    /** Las filas de productos nuevos con stock inicial y sin costo. */
    val stockWithoutCost: Int = 0,
) {
    /** Cuántas filas tienen al menos un error. */
    val errorRows: Int get() = issues.map { it.row }.distinct().size

    /** Cuántas filas ya existen en el negocio, se puedan actualizar o no. */
    val existingRows: Int get() = existing.size + locked.size

    /** Cuántas filas se escriben con [policy]. */
    fun toApply(policy: ImportExistingPolicy): Int = when (policy) {
        ImportExistingPolicy.SKIP -> ready.size
        ImportExistingPolicy.UPDATE -> ready.size + existing.size
    }
}
