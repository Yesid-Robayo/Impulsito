package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget

/**
 * Lo que falta para poder revisar las filas: los datos obligatorios que ninguna columna trae y los
 * que traen dos columnas a la vez. Con cualquiera de los dos, la pantalla de relacionar columnas
 * marca lo que hay que corregir y no avanza.
 */
data class ImportMappingCheck(
    /** Las claves obligatorias sin columna, en el orden en que se ofrecen. */
    val missing: List<String>,
    /** Las claves que eligieron dos o más columnas, en el orden en que se ofrecen. */
    val duplicated: List<String>,
) {
    /** Si se puede pasar a revisar las filas. */
    val isComplete: Boolean get() = missing.isEmpty() && duplicated.isEmpty()
}

/** Revisa la relación que eligió la persona entre las columnas del archivo y los datos. */
object ImportMappingRules {
    /** Revisa [columns], el dato de cada columna o `null` si no se importa, contra [target]. */
    fun check(columns: List<String?>, target: ImportTarget): ImportMappingCheck {
        val chosen = columns.filterNotNull()
        val keys = target.fields.map { it.key }
        return ImportMappingCheck(
            missing = target.fields.filter { it.required && it.key !in chosen }.map { it.key },
            duplicated = keys.filter { key -> chosen.count { it == key } > 1 },
        )
    }
}
