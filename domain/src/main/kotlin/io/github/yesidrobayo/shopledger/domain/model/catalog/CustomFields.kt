package io.github.yesidrobayo.shopledger.domain.model.catalog

// Los campos propios y las etiquetas de los productos: datos que la persona agrega para buscar y
// agrupar. Nunca entran en un cálculo de dinero ni de stock.

/** Cómo se interpreta el texto guardado de un campo propio. */
enum class CustomFieldType {
    /** Texto libre, de 1 a 200 caracteres. */
    TEXT,

    /** Un número decimal, guardado en forma neutra: `-1234.5`, sin separador de miles. */
    NUMBER,

    /** Una fecha del calendario, guardada como `2026-11-14`. */
    DATE,

    /** Sí o no, guardado como `true` o `false`. */
    BOOLEAN,
    ;

    companion object {
        /** El tipo guardado con [name], o `null` si es desconocido, como el de una copia más nueva. */
        fun fromStored(name: String): CustomFieldType? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Un campo propio, como `Marca` o `Garantía`.
 *
 * @param type su tipo, o `null` si la base guarda uno que esta versión no conoce: el campo se
 *   muestra, pero no se llena.
 * @param productCount cuántos productos tienen un valor en él.
 */
data class CustomField(
    val id: Long,
    val name: String,
    val type: CustomFieldType?,
    val sortOrder: Long,
    val isArchived: Boolean,
    val productCount: Int,
)

/** Lo que la persona escribe al crear o editar un campo propio. */
data class CustomFieldDraft(val name: String, val type: CustomFieldType)

/** Una etiqueta que agrupa productos de cualquier categoría. [productCount] la llevan. */
data class ProductTag(val id: Long, val name: String, val productCount: Int)

/** Por qué un nombre de campo o de etiqueta no sirve. */
enum class ExtraNameIssue {
    /** Vacío, o solo marcas sin letra. */
    EMPTY,

    /** Pasa de 40 caracteres. */
    TOO_LONG,
}

/** Por qué un valor no sirve para el tipo de su campo. */
enum class CustomValueIssue {
    /** Pasa de 200 caracteres. */
    TOO_LONG,

    /** No es un número en forma neutra, o tiene demasiadas cifras. */
    NOT_A_NUMBER,

    /** No es una fecha del calendario. */
    NOT_A_DATE,

    /** No es `true` ni `false`. */
    NOT_A_BOOLEAN,

    /** El campo es de un tipo que esta versión no conoce: no se escribe. */
    UNKNOWN_TYPE,
}

/**
 * Lo propio de un producto en su formulario: los campos vigentes, sus valores y las etiquetas.
 *
 * @param values el texto guardado de cada campo con valor, por id de campo; incluye los de campos
 *   archivados, que no se muestran pero se conservan.
 * @param tagIds las etiquetas que lleva el producto.
 */
data class ProductExtras(
    val fields: List<CustomField>,
    val values: Map<Long, String>,
    val tags: List<ProductTag>,
    val tagIds: Set<Long>,
)
