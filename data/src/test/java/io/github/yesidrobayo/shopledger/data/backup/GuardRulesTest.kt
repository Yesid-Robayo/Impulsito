package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.guard.CatalogTrigger
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaCatalog
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaVersionCatalog
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las reglas por fila que el validador lee del texto de las guardas: las de toda fila sí, las que
 * dependen de otra columna no, y lo que una columna admite es la unión de todas sus guardas.
 */
class GuardRulesTest {
    private val rules = GuardRules.of(SchemaCatalog.current)

    @Test
    fun `each closed list of the schema is the list of its domain type`() {
        fun names(entries: List<Enum<*>>) = entries.map { it.name }.toSet()

        assertEquals(names(StockMovementType.entries), enumOf("stock_movements", "type"))
        assertEquals(names(ProductStockMode.entries), enumOf("products", "stock_mode"))
        assertEquals(names(ProductUsage.entries), enumOf("products", "usage"))
        assertEquals(names(LineStockEffect.entries), enumOf("sale_lines", "stock_effect"))
        assertEquals(names(UnitKind.entries), enumOf("units", "kind"))
        assertEquals(names(LocationKind.entries), enumOf("locations", "kind"))
        assertEquals(names(PaymentMethodKind.entries), enumOf("payment_methods", "kind"))
        assertEquals(
            names(PaymentMethodKind.entries),
            enumOf("sale_payments", "method_kind_snapshot"),
        )
        assertEquals(names(ColorPalette.entries), enumOf("app_settings", "color_palette"))
    }

    @Test
    fun `a document status is read from the guards that create and void it`() {
        val statuses = DocumentStatus.entries.map { it.name }.toSet()
        for (table in DOCUMENTS) assertEquals(table, statuses, enumOf(table, "status"))
    }

    @Test
    fun `a value only allowed under a condition is not a rule for every row`() {
        // El tipo de dinero se acota por dirección dentro de condiciones: la lista completa sigue
        // siendo la de la guarda de inserción.
        assertEquals(16, enumOf("money_movements", "type")?.size)
        // Los booleanos no son listas de texto.
        assertNull(rules.enums[ColumnRef("products", "tracks_lots")])
        assertNull(rules.enums[ColumnRef("price_lists", "base_price_list_id")])
    }

    @Test
    fun `ranges and lengths are read for every row, also for nullable columns`() {
        assertEquals(1L..1_000_000_000L, rules.ranges[ColumnRef("sale_lines", "quantity_milli")])
        assertEquals(0L..1_000_000_000_000L, rules.ranges[ColumnRef("products", "sale_price")])
        assertEquals(
            -1_000_000_000_000L..1_000_000_000_000L,
            rules.ranges[ColumnRef("customers", "points_balance")],
        )
        assertEquals(0L..9_900L, rules.ranges[ColumnRef("products", "target_margin_bp")])
        assertEquals(80L, rules.maxLengths[ColumnRef("products", "name")])
        assertEquals(64L, rules.maxLengths[ColumnRef("products", "barcode")])
        assertEquals(40L, rules.maxLengths[ColumnRef("sale_lines", "tax_name_snapshot")])
        assertEquals(200L, rules.maxLengths[ColumnRef("sales", "void_note")])
    }

    @Test
    fun `only rules that hold for every row are taken`() {
        val catalog =
            catalogWith(
                """
                CREATE TRIGGER guard_things_insert BEFORE INSERT ON things
                WHEN NOT IFNULL((
                  NEW.kind IN ('A', 'B')
                  AND NEW.size BETWEEN 1 AND 10
                  AND (NEW.kind <> 'A' OR NEW.weight BETWEEN 1 AND 5)
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 30))
                  AND (NEW.label IS NULL OR NEW.code IN ('X', 'Y'))
                  AND NEW.flag IN (0, 1)
                  AND NEW.mood NOT IN ('SAD')
                  AND NEW.text = 'it''s (not) a list'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:things'); END
                """.trimIndent(),
                """
                CREATE TRIGGER guard_things_update BEFORE UPDATE ON things
                WHEN NOT IFNULL((
                  NEW.kind IN ('B', 'C') AND NEW.size BETWEEN -5 AND 3
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:things'); END
                """.trimIndent(),
                "CREATE TRIGGER guard_things_no_delete BEFORE DELETE ON things " +
                    "BEGIN SELECT RAISE(ABORT, 'guard:things'); END",
            )
        val derived = GuardRules.of(catalog)

        assertEquals(setOf("A", "B", "C"), derived.enums[ColumnRef("things", "kind")])
        assertEquals(-5L..10L, derived.ranges[ColumnRef("things", "size")])
        assertEquals(30L, derived.maxLengths[ColumnRef("things", "note")])
        // Dependen de otra columna, o no son listas de texto.
        assertNull(derived.ranges[ColumnRef("things", "weight")])
        assertNull(derived.enums[ColumnRef("things", "code")])
        assertNull(derived.enums[ColumnRef("things", "flag")])
        assertNull(derived.enums[ColumnRef("things", "mood")])
        // Un texto fijo en una sola guarda no define una lista.
        assertNull(derived.enums[ColumnRef("things", "text")])
    }

    @Test
    fun `a status fixed by a single guard is not a closed list`() {
        val catalog =
            catalogWith(
                """
                CREATE TRIGGER guard_things_insert BEFORE INSERT ON things
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:things'); END
                """.trimIndent(),
                """
                CREATE TRIGGER guard_other_update BEFORE UPDATE ON things
                WHEN NOT IFNULL((
                  (NEW.status = 'VOIDED')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:things'); END
                """.trimIndent(),
            )
        assertTrue(GuardRules.of(catalog).enums.isEmpty())
    }

    @Test
    fun `every derived list and range is well formed`() {
        assertFalse(rules.enums.isEmpty())
        for ((ref, values) in rules.enums) {
            assertTrue("$ref", values.isNotEmpty())
            assertTrue("$ref", values.all { it.matches(Regex("[A-Z][A-Z0-9_]*")) })
            assertTrue("$ref", SchemaCatalog.current.table(ref.table) != null)
        }
        for ((ref, range) in rules.ranges) assertTrue("$ref", range.first <= range.last)
    }

    private fun enumOf(table: String, column: String): Set<String>? =
        rules.enums[ColumnRef(table, column)]

    private fun catalogWith(vararg triggers: String): SchemaVersionCatalog = SchemaVersionCatalog(
        version = 1,
        identityHash = "0",
        tables = emptyList(),
        triggers =
            triggers.map { sql ->
                val name = sql.substringAfter("CREATE TRIGGER ").substringBefore(' ')
                CatalogTrigger(name, "things", sql)
            },
    )

    private companion object {
        val DOCUMENTS =
            listOf(
                "sales",
                "purchases",
                "expenses",
                "sale_returns",
                "purchase_returns",
                "stock_transfers",
                "production_batches",
            )
    }
}
