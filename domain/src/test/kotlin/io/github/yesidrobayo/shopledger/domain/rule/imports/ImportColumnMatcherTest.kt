package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFieldKind
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.port.ImportHeaderLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconocer columnas en los tres idiomas, con encabezados escritos a mano como los de los recursos
 * de la app: un archivo exportado en español entra con la app en inglés, porque el reconocimiento
 * no depende del idioma elegido.
 */
class ImportColumnMatcherTest {
    private val headers =
        mapOf(
            "name" to listOf("Name", "Nombre", "Nome"),
            "category" to listOf("Category", "Categoría", "Categoria"),
            "sale_price" to listOf("Sale price", "Precio de venta", "Preço de venda"),
            "average_cost" to listOf("Average cost", "Costo promedio", "Custo médio"),
            "on_hand" to listOf("Stock", "Stock", "Estoque"),
            "stock_value" to listOf("Stock value", "Valor del stock", "Valor do estoque"),
            "barcode" to listOf("Barcode", "Código de barras", "Código de barras"),
        )
    private val matcher = ImportColumnMatcher { key -> headers[key].orEmpty() }

    @Test
    fun `a file exported in spanish is recognized whatever the language of the app`() {
        val spanish =
            listOf(
                "Nombre",
                "Categoría",
                "Código de barras",
                "Precio de venta",
                "Costo promedio",
                "Valor del stock",
                "Stock",
            )

        val matches = matcher.match(spanish, ImportTarget.PRODUCTS)

        assertEquals(
            listOf(
                ColumnMatch.Field("name"),
                ColumnMatch.Field("category"),
                ColumnMatch.Field("barcode"),
                ColumnMatch.Field("sale_price"),
                ColumnMatch.Field("average_cost"),
                ColumnMatch.Ignored("stock_value"),
                ColumnMatch.Field("on_hand"),
            ),
            matches,
        )
        assertTrue(matcher.missingRequired(matches, ImportTarget.PRODUCTS).isEmpty())
    }

    @Test
    fun `portuguese headers, english keys and folded spellings are recognized`() {
        val header =
            listOf(
                "NOME",
                "  preco de venda ",
                "sale_price",
                "custo-medio",
                "'Estoque",
                "min_stock",
            )

        assertEquals(
            listOf(
                ColumnMatch.Field("name"),
                ColumnMatch.Field("sale_price"),
                // La segunda columna del mismo dato queda para que la persona decida.
                ColumnMatch.Unmatched,
                ColumnMatch.Field("average_cost"),
                ColumnMatch.Field("on_hand"),
                // Una clave se reconoce a sí misma, aunque no tenga encabezado.
                ColumnMatch.Field("min_stock"),
            ),
            matcher.match(header, ImportTarget.PRODUCTS),
        )
    }

    @Test
    fun `unknown and blank headers go to the mapping step`() {
        val header = listOf("precio", "", "   ", "Proveedor")
        val matches = matcher.match(header, ImportTarget.PRODUCTS)

        assertEquals(List(4) { ColumnMatch.Unmatched }, matches)
        assertEquals(listOf("name"), matcher.missingRequired(matches, ImportTarget.PRODUCTS))
    }

    @Test
    fun `a name that two columns share in different languages recognizes neither`() {
        val clash =
            ImportColumnMatcher { key ->
                when (key) {
                    "phone" -> listOf("Teléfono", "Contacto")
                    "email" -> listOf("Correo", "contacto", " ")
                    else -> emptyList()
                }
            }

        val matches = clash.match(listOf("Contacto", "Telefono", "correo"), ImportTarget.CUSTOMERS)

        assertEquals(
            listOf(ColumnMatch.Unmatched, ColumnMatch.Field("phone"), ColumnMatch.Field("email")),
            matches,
        )
    }

    @Test
    fun `every target has its required fields and stable keys`() {
        val required = ImportTarget.entries.associateWith { target ->
            target.fields.filter { it.required }.map { it.key }
        }

        assertEquals(listOf("name"), required[ImportTarget.PRODUCTS])
        assertEquals(listOf("name"), required[ImportTarget.CUSTOMERS])
        assertEquals(listOf("name"), required[ImportTarget.SUPPLIERS])
        assertEquals(listOf("month", "change_percent"), required[ImportTarget.PRICE_INDEX])
        assertEquals(ImportFieldKind.AMOUNT, ImportTarget.PRODUCTS.field("sale_price")?.kind)
        assertNull(ImportTarget.SUPPLIERS.field("sale_price"))
        ImportTarget.entries.forEach { target ->
            val keys = target.fields.map { it.key } + target.ignoredColumns
            assertEquals(target.name, keys.size, keys.toSet().size)
        }
    }

    @Test
    fun `folding ignores case, accents, spaces, separators and the protecting apostrophe`() {
        assertEquals("precio de venta", ImportColumnMatcher.fold("  'PRECIO_de-Venta "))
        assertEquals("categoria", ImportColumnMatcher.fold("Categoría"))
        assertEquals("", ImportColumnMatcher.fold(" ' "))
    }

    @Test
    fun `labels are asked once per key and match`() {
        val asked = mutableListOf<String>()
        val counting = ImportHeaderLabels { key ->
            asked += key
            emptyList()
        }

        ImportColumnMatcher(counting).match(listOf("month"), ImportTarget.PRICE_INDEX)

        assertEquals(listOf("month", "change_percent", "created_at"), asked)
    }
}
