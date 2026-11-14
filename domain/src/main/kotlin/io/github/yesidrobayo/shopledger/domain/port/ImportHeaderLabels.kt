package io.github.yesidrobayo.shopledger.domain.port

/**
 * Los nombres con que puede llegar una columna en un archivo para importar: su encabezado en cada
 * idioma que trae la app y sus alias. Lo implementa la interfaz, que tiene los textos.
 *
 * No depende del idioma elegido: un archivo exportado en español entra igual con la app en inglés.
 */
fun interface ImportHeaderLabels {
    /** Los encabezados y alias de la columna de clave [key], en cualquier orden y sin la clave. */
    fun labels(key: String): List<String>
}
