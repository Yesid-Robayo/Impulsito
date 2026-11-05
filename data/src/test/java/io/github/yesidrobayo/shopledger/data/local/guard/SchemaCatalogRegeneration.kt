package io.github.yesidrobayo.shopledger.data.local.guard

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * El generador de `SchemaCatalog`. No es una prueba: corre como una para usar las clases de `:data`
 * tal como las compila Gradle, y solo escribe con la tarea `generateSchemaCatalog`:
 *
 * ```
 * .\gradlew.bat :data:generateSchemaCatalog
 * ```
 *
 * En las pruebas de siempre queda omitida. Es [SchemaCatalogTest] la que falla si el catálogo no
 * coincide con el esquema, y dice que se corra esta tarea.
 */
class SchemaCatalogRegeneration {
    @Test
    fun writeTheCatalog() {
        assumeTrue(
            "Solo la tarea generateSchemaCatalog escribe el catálogo",
            System.getProperty(WRITE_PROPERTY) == "true",
        )
        val changed = SchemaCatalogSources.fromSystemProperties().write()
        // La salida de la tarea dice qué cambió. No hay datos del negocio: solo nombres de archivo.
        println(
            if (changed.isEmpty()) {
                "El catálogo del esquema ya estaba al día"
            } else {
                "Catálogo del esquema reescrito: ${changed.joinToString { it.name }}"
            },
        )
    }

    companion object {
        /** La propiedad que solo pasa la tarea `generateSchemaCatalog`. */
        const val WRITE_PROPERTY = "schemaCatalog.write"
    }
}
