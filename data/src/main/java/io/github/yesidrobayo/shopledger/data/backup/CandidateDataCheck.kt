package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaVersionCatalog
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateFinding
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.rule.backup.RegionalShape
import io.github.yesidrobayo.shopledger.domain.rule.backup.StoredReading
import io.github.yesidrobayo.shopledger.domain.rule.backup.StoredValueRules

/**
 * Lo que el validador sabe de la copia y de la base que reemplazaría, fuera del archivo mismo.
 *
 * @property expectedCopy lo que dicen los metadatos de la copia en el destino, o `null` si no los
 *   hay: al crear una copia, con un archivo elegido por la persona y al deshacer una restauración.
 * @property activeHasData si la base activa tiene datos del negocio.
 * @property emptyCopyConfirmed si la persona ya decidió, de forma expresa, reemplazar esos datos con
 *   una copia vacía.
 */
internal data class CandidateContext(
    val expectedCopy: ExpectedCopy? = null,
    val activeHasData: Boolean = false,
    val emptyCopyConfirmed: Boolean = false,
) {
    companion object {
        /** El de una copia recién creada: ni metadatos que comparar, ni una base que reemplazar. */
        val NEW_COPY = CandidateContext()
    }
}

/**
 * La identidad de una copia según sus metadatos en el destino: el teléfono que la escribió, su
 * generación y la versión del esquema.
 */
internal data class ExpectedCopy(val deviceId: String, val generation: Long, val schemaVersion: Int)

/**
 * Revisa el significado de los datos de una base candidata, ya comprobada su estructura: la lista
 * cerrada de comprobaciones, cada una con su clase fija.
 *
 * Solo lee. Cada hallazgo cuenta casos, nunca el dato que falló. Ninguna comprobación compara un
 * dato con una lista de países, monedas o zonas: de la moneda, el formato regional, la zona y los
 * idiomas solo se revisa la forma.
 *
 * Los tipos, las listas cerradas, los rangos y los largos salen de las guardas del catálogo de su
 * versión ([GuardRules]): son las mismas reglas que la base impone al escribir, ahora sobre lo que
 * ya está guardado. La fecha de negocio de una fila vieja no se compara con su instante: la zona del
 * negocio pudo cambiar después de escribirla, y cambiarla no reescribe filas.
 */
internal class CandidateDataCheck(
    private val catalog: SchemaVersionCatalog,
    private val schemaVersion: Int,
    private val context: CandidateContext,
) {
    private val rules = GuardRules.of(catalog)

    /** Los hallazgos sobre la base de [connection], uno por comprobación, en el orden de la lista. */
    fun findings(connection: SQLiteConnection): List<CandidateFinding> {
        val cases = mutableMapOf<CandidateCheck, Long>()
        fun add(check: CandidateCheck, count: Long) {
            if (check.appliesWith(context.expectedCopy != null) && count > 0) {
                cases.merge(check, count, Long::plus)
            }
        }
        add(CandidateCheck.SCHEMA_IDENTITY, identityProblems(connection))
        add(CandidateCheck.FOREIGN_KEYS, connection.countRows("PRAGMA foreign_key_check"))
        add(CandidateCheck.STORAGE_TYPES, storageTypeProblems(connection))
        add(CandidateCheck.LEDGER_ENUMS, ledgerEnumProblems(connection))
        add(CandidateCheck.VALUE_RANGES, rangeProblems(connection))
        add(CandidateCheck.COPY_METADATA_MATCH, copyMetadataProblems(connection))
        add(CandidateCheck.EMPTY_OVER_DATA, emptyOverDataProblems(connection))
        for (query in InvariantQueries.ALL) add(query.check, connection.countRows(query.sql))
        val costs = CostReplays.of(connection)
        add(CandidateCheck.COST_AND_TOTALS, costs.count { it.drifted }.toLong())
        add(CandidateCheck.UNDERIVABLE_COHERENCE, costs.count { !it.replayed }.toLong())
        add(CandidateCheck.OPEN_DATA, openDataProblems(connection))
        add(CandidateCheck.TABLE_RULES, tableRuleProblems(connection))
        add(CandidateCheck.PRESENTATION_PREFERENCES, paletteProblems(connection))
        return CandidateCheck.entries.mapNotNull { check ->
            cases[check]?.let { CandidateFinding(check, it) }
        }
    }

    /** La huella de Room debe ser la de esta versión, en una sola fila. */
    private fun identityProblems(connection: SQLiteConnection): Long {
        if (!connection.hasTable(ROOM_MASTER_TABLE)) return 1
        val hashes = connection.prepare("SELECT identity_hash FROM room_master_table").use {
            buildList { while (it.step()) add(it.textOrNull(0)) }
        }
        return if (hashes == listOf(catalog.identityHash)) 0 else 1
    }

    /**
     * Toda columna entera guarda un entero o nada, y toda columna de texto un texto o nada. SQLite
     * admite un texto en una columna entera, y eso rompería la lectura o las cuentas.
     */
    private fun storageTypeProblems(connection: SQLiteConnection): Long =
        catalog.tables.sumOf { table ->
            val conditions = table.columns.mapNotNull { column ->
                val expected = STORAGE_CLASSES[column.type.uppercase()] ?: return@mapNotNull null
                "typeof(${identifier(column.name)}) NOT IN ('$expected', 'null')"
            }
            if (conditions.isEmpty()) {
                0L
            } else {
                connection.singleLong(
                    "SELECT COUNT(*) FROM ${identifier(table.name)} WHERE " +
                        conditions.joinToString(" OR "),
                )
            }
        }

    /**
     * Cada columna de tipo guarda un valor de su lista cerrada. El tipo de método de pago y la paleta
     * no: los revisan sus propias comprobaciones, como datos abiertos y como preferencia.
     *
     * Aquí va también una moneda sin confirmar en una base que ya registró dinero: la moneda del
     * libro no puede quedar sin elegir con el libro cerrado a otra.
     */
    private fun ledgerEnumProblems(connection: SQLiteConnection): Long {
        var problems = 0L
        for ((ref, allowed) in rules.enums) {
            if (ref in OPEN_ENUMS) continue
            problems += connection.textCounts(
                "SELECT ${identifier(ref.column)}, COUNT(*) FROM ${identifier(ref.table)} " +
                    "WHERE ${identifier(ref.column)} IS NOT NULL GROUP BY 1",
            ).filterKeys { it !in allowed }.values.sum()
        }
        problems += connection.singleLong(
            "SELECT COUNT(*) FROM app_settings " +
                "WHERE currency_locked_at IS NOT NULL AND currency_code = '$UNCONFIRMED_CURRENCY'",
        )
        return problems
    }

    /**
     * Montos, cantidades y largos dentro de lo que admiten las guardas, instantes positivos y
     * fechas de negocio que existen. Cuenta filas: una fila con varios valores fuera de rango es un
     * solo caso.
     */
    private fun rangeProblems(connection: SQLiteConnection): Long = catalog.tables.sumOf { table ->
        val conditions = mutableListOf<String>()
        for (column in table.columns) {
            val ref = ColumnRef(table.name, column.name)
            val name = identifier(column.name)
            rules.ranges[ref]?.let {
                conditions += "($name IS NOT NULL AND $name NOT BETWEEN ${it.first} AND ${it.last})"
            }
            rules.maxLengths[ref]?.let { conditions += "length($name) > $it" }
            when (column.name) {
                OCCURRED_AT -> conditions += "$name <= 0"
                BUSINESS_DATE -> conditions += "date($name) IS NOT $name"
            }
        }
        if (conditions.isEmpty()) {
            0L
        } else {
            connection.singleLong(
                "SELECT COUNT(*) FROM ${identifier(table.name)} WHERE " +
                    conditions.joinToString(" OR "),
            )
        }
    }

    /** El sello de la copia y su versión coinciden con lo que dicen sus metadatos. */
    private fun copyMetadataProblems(connection: SQLiteConnection): Long {
        val expected = context.expectedCopy ?: return 0
        val matches = connection.prepare(
            "SELECT copy_device_id, copy_generation FROM backup_state WHERE id = 1",
        ).use { statement ->
            statement.step() &&
                statement.textOrNull(0) == expected.deviceId &&
                !statement.isNull(1) &&
                statement.getLong(1) == expected.generation
        }
        return if (matches && schemaVersion == expected.schemaVersion) 0 else 1
    }

    /** Una base sin datos del negocio no reemplaza una con datos sin una decisión expresa. */
    private fun emptyOverDataProblems(connection: SQLiteConnection): Long {
        if (!context.activeHasData || context.emptyCopyConfirmed) return 0
        val empty = EMPTY_TABLES.none { table ->
            connection.countRows("SELECT 1 FROM ${identifier(table)} LIMIT 1") > 0
        }
        return if (empty) 1 else 0
    }

    /**
     * Datos abiertos: un tipo de método de pago desconocido se lee como "otro", y de la moneda, el
     * formato regional, la zona del negocio y el idioma solo se revisa la forma.
     */
    private fun openDataProblems(connection: SQLiteConnection): Long {
        var problems = 0L
        for (ref in OPEN_ENUMS - PALETTE) {
            problems += connection.textCounts(
                "SELECT ${identifier(ref.column)}, COUNT(*) FROM ${identifier(ref.table)} " +
                    "WHERE ${identifier(ref.column)} IS NOT NULL GROUP BY 1",
            ).entries.sumOf { (stored, count) ->
                val reading = StoredValueRules.readPaymentMethodKind(stored)
                if (reading is StoredReading.Substituted) count else 0L
            }
        }
        for (table in catalog.tables) {
            for (column in table.columns) {
                val shape = regionalShapeOf(column.name) ?: continue
                problems += connection.textCounts(
                    "SELECT ${identifier(column.name)}, COUNT(*) FROM ${identifier(table.name)} " +
                        "WHERE typeof(${identifier(column.name)}) = 'text' GROUP BY 1",
                ).entries.sumOf { (stored, count) ->
                    if (StoredValueRules.checkRegionalShape(shape, stored) == null) 0L else count
                }
            }
        }
        return problems
    }

    /** Las reglas propias de algunas tablas, que no son de una sola fila. */
    private fun tableRuleProblems(connection: SQLiteConnection): Long =
        TABLE_RULES.sumOf { connection.countRows(it) } +
            catalog.tables.sumOf { table ->
                table.columns.sumOf { column ->
                    val condition = COLUMN_RULES[column.name] ?: return@sumOf 0L
                    connection.singleLong(
                        "SELECT COUNT(*) FROM ${identifier(table.name)} WHERE " +
                            condition.replace(COLUMN, identifier(column.name)),
                    )
                }
            }

    /** La paleta guardada es una que la app conoce. */
    private fun paletteProblems(connection: SQLiteConnection): Long = connection.textCounts(
        "SELECT color_palette, COUNT(*) FROM app_settings GROUP BY 1",
    ).filterKeys { stored -> ColorPalette.entries.none { it.name == stored } }.values.sum()

    private companion object {
        const val ROOM_MASTER_TABLE = "room_master_table"
        const val OCCURRED_AT = "occurred_at"
        const val BUSINESS_DATE = "business_date"

        /** La moneda que lleva la base antes de que la persona la confirme. */
        const val UNCONFIRMED_CURRENCY = "XXX"

        /** La clase de almacenamiento de SQLite que admite cada tipo declarado, además de nada. */
        val STORAGE_CLASSES = mapOf("INTEGER" to "integer", "TEXT" to "text")

        val PALETTE = ColumnRef("app_settings", "color_palette")

        /** Columnas con lista en la guarda que el validador revisa aparte. */
        val OPEN_ENUMS =
            setOf(
                ColumnRef("payment_methods", "kind"),
                ColumnRef("sale_payments", "method_kind_snapshot"),
                PALETTE,
            )

        /** Las tablas cuyas filas hacen que una base tenga datos del negocio. */
        val EMPTY_TABLES =
            listOf(
                "products",
                "suppliers",
                "customers",
                "sales",
                "purchases",
                "expenses",
                "quotes",
                "orders",
                "money_movements",
            )

        fun regionalShapeOf(column: String): RegionalShape? = when {
            column == "currency_code" || column.endsWith("_currency_code") ->
                RegionalShape.CURRENCY_CODE

            column == "format_locale" || column == "language_tag" -> RegionalShape.LANGUAGE_TAG

            column == "business_time_zone" -> RegionalShape.TIME_ZONE_ID

            else -> null
        }

        const val COLUMN = "{column}"

        /** Reglas de una columna, en cualquier tabla que la tenga. */
        val COLUMN_RULES =
            mapOf(
                "factor_milli" to "$COLUMN < 1",
                "rate_micro" to "$COLUMN <= 0",
                "sha256" to "length($COLUMN) <> 64 OR $COLUMN GLOB '*[^0-9a-f]*'",
            )

        /** Reglas entre filas: cada fila que devuelven es un caso. */
        val TABLE_RULES =
            listOf(
                // Una lista de precios que, siguiendo su base, vuelve a sí misma. El recorrido se
                // corta en tantos pasos como listas hay.
                """
                WITH RECURSIVE chain(start, current, steps) AS (
                  SELECT id, base_price_list_id, 1 FROM price_lists WHERE base_price_list_id IS NOT NULL
                  UNION ALL
                  SELECT c.start, p.base_price_list_id, c.steps + 1
                  FROM chain c JOIN price_lists p ON p.id = c.current
                  WHERE p.base_price_list_id IS NOT NULL
                    AND c.current <> c.start
                    AND c.steps <= (SELECT COUNT(*) FROM price_lists)
                )
                SELECT DISTINCT start FROM chain WHERE current = start
                """.trimIndent(),
                // Una sola caja abierta por cuenta.
                """
                SELECT money_account_id FROM cash_sessions WHERE status = 'OPEN'
                GROUP BY money_account_id HAVING COUNT(*) > 1
                """.trimIndent(),
                // Cada adjunto es de exactamente un documento.
                "SELECT id FROM document_attachments WHERE (expense_id IS NULL) = (purchase_id IS NULL)",
            )
    }
}
