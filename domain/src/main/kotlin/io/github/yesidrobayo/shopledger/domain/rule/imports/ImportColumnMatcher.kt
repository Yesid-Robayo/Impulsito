package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.port.ImportHeaderLabels
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Reconoce las columnas de un archivo para importar por su encabezado, en cualquier idioma: por la
 * clave estable en inglés, como `sale_price`, por el encabezado de cualquier idioma que trae la
 * app o por un alias. Lo que no reconoce queda sin relacionar, para que la persona lo elija a
 * mano.
 *
 * Los encabezados se comparan plegados: sin mayúsculas, tildes ni espacios de más, con el guion
 * bajo y el guion como espacio, y sin el apóstrofo que una hoja de cálculo deja delante. Así
 * `Categoría`, `CATEGORIA` y `categoria` son lo mismo, y `sale_price` y `Sale price` también.
 *
 * Un nombre que en dos idiomas da columnas distintas no reconoce ninguna: elegir una sería
 * adivinar. Si dos columnas del archivo apuntan al mismo dato, se queda la primera y la otra queda
 * sin relacionar, para que la persona decida.
 */
class ImportColumnMatcher(private val labels: ImportHeaderLabels) {
    /** La relación de cada columna de [header] con un dato de [target], en el mismo orden. */
    fun match(header: List<String>, target: ImportTarget): List<ColumnMatch> {
        val index = indexOf(target)
        val taken = mutableSetOf<String>()
        return header.map { name ->
            val key = index[fold(name)]
            when {
                key == null -> ColumnMatch.Unmatched
                key in target.ignoredColumns -> ColumnMatch.Ignored(key)
                taken.add(key) -> ColumnMatch.Field(key)
                else -> ColumnMatch.Unmatched
            }
        }
    }

    /** Las claves de los datos obligatorios de [target] que ninguna columna trae en [matches]. */
    fun missingRequired(matches: List<ColumnMatch>, target: ImportTarget): List<String> {
        val present = matches.filterIsInstance<ColumnMatch.Field>().map { it.key }.toSet()
        return target.fields.filter { it.required && it.key !in present }.map { it.key }
    }

    /**
     * Cada nombre plegado con la clave que reconoce, o `null` si reconoce más de una. Una clave
     * siempre se reconoce a sí misma, aunque no tenga encabezado.
     */
    private fun indexOf(target: ImportTarget): Map<String, String?> {
        val index = HashMap<String, String?>()
        val keys = target.fields.map { it.key } + target.ignoredColumns
        for (key in keys) {
            for (label in listOf(key) + labels.labels(key)) {
                val folded = fold(label)
                if (folded.isEmpty()) continue
                index[folded] = if (folded in index && index[folded] != key) null else key
            }
        }
        return index
    }

    companion object {
        private const val NEUTRALIZER = '\''

        /** [name] plegado para comparar encabezados. */
        fun fold(name: String): String = TextNormalizer.normalize(
            name.trim().removePrefix(
                NEUTRALIZER.toString(),
            ).replace('_', ' ').replace('-', ' '),
        )
    }
}

/** La relación de una columna del archivo con los datos que se importan. */
sealed interface ColumnMatch {
    /** La columna trae el dato de clave [key]. */
    data class Field(val key: String) : ColumnMatch

    /** Es una columna conocida que no se importa, como un saldo. */
    data class Ignored(val key: String) : ColumnMatch

    /** No se reconoció: la persona elige qué dato trae, o que no se importe. */
    data object Unmatched : ColumnMatch
}
