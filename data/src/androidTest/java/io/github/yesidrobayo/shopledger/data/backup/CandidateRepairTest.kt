package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaCatalog
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateFinding
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que no se rechaza. Un saldo de cabecera o un total corrido pide recalcular, y al recalcular
 * queda como la suma del libro y las líneas, con las guardas idénticas al catálogo y sin tocar
 * ningún libro. Lo que no se puede derivar solo se cuenta, y el libro queda como estaba. Todos los
 * datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class CandidateRepairTest {
    @Before
    fun clean() {
        CandidateFixtures.clean()
    }

    @After
    fun cleanUp() {
        CandidateFixtures.clean()
    }

    @Test
    fun aDriftedStockIsRepairedFromTheLedger() = assertRepaired(
        "stock corrido",
        setOf(CandidateCheck.STOCK_ON_HAND, CandidateCheck.HEADER_BALANCES),
    ) {
        withoutGuards("products") {
            updateFirst(
                "products",
                "on_hand_milli = on_hand_milli + 1000",
                "stock_mode = 'TRACKED'",
            )
        }
    }

    @Test
    fun aDriftedSaleTotalIsRepairedOnTheCandidateWithItsGuardsIntact() {
        val candidate = CandidateFixtures.copyOf("sale-total")
        corrupt(candidate) {
            withoutGuards("sales") {
                updateFirst("sales", "total = total + 1, net_total = net_total + 1")
            }
        }
        val findings = validate(candidate).findings()
        assertEquals(1L, findings[CandidateCheck.COST_AND_TOTALS])

        repairAndAssertClean("total de venta corrido", candidate)
        val expected = SchemaCatalog.current.triggers.associate { it.name to it.sql }
        assertEquals(expected, guardsIn(candidate))
    }

    @Test
    fun aDriftedPurchaseTotalIsRepaired() = assertRepaired(
        "total de compra corrido",
        setOf(CandidateCheck.COST_AND_TOTALS),
    ) {
        withoutGuards("purchases") {
            updateFirst("purchases", "cost_total = cost_total + 7")
        }
    }

    @Test
    fun aDriftedCostIsRepairedByReplayingTheLedger() = assertRepaired(
        "costo corrido",
        setOf(CandidateCheck.COST_AND_TOTALS),
    ) {
        withoutGuards("products") {
            updateFirst(
                "products",
                "average_cost = average_cost + 3, stock_value = stock_value + 5",
                "stock_mode = 'TRACKED' AND on_hand_milli > 0",
            )
        }
    }

    @Test
    fun aDriftedOrMissingStockBalanceIsRepaired() {
        assertRepaired("saldo por ubicación corrido", setOf(CandidateCheck.HEADER_BALANCES)) {
            withoutGuards("stock_balances") {
                updateFirst(
                    "stock_balances",
                    "on_hand_milli = on_hand_milli - 1",
                    "on_hand_milli <> 0",
                )
            }
        }
        assertRepaired("saldo por ubicación que falta", setOf(CandidateCheck.HEADER_BALANCES)) {
            withoutGuards("stock_balances") {
                execSQL(
                    "DELETE FROM stock_balances " +
                        "WHERE id = ${firstId("stock_balances", "on_hand_milli <> 0")}",
                )
            }
        }
    }

    @Test
    fun aStaleLowStockAlertIsResolved() = assertRepaired(
        "alerta que ya no corresponde",
        setOf(CandidateCheck.HEADER_BALANCES),
    ) {
        val product =
            firstId("products", "stock_mode = 'TRACKED' AND on_hand_milli > min_stock_milli")
        withoutGuards("low_stock_alerts") {
            execSQL(
                "INSERT INTO low_stock_alerts (product_id, on_hand_milli_at_trigger, " +
                    "min_stock_milli_at_trigger, notification_status, occurred_at, " +
                    "business_date, resolved_at) " +
                    "VALUES ($product, 0, 0, 'SHOWN', 1, '2026-10-01', NULL)",
            )
        }
    }

    @Test
    fun driftedHeaderBalancesAreRepairedFromTheirLedgers() {
        val drifts =
            listOf(
                Triple("customers", "balance", "customer_ledger_entries"),
                Triple("suppliers", "balance", "supplier_ledger_entries"),
                Triple("money_accounts", "balance", "money_movements"),
                Triple("customers", "points_balance", "loyalty_ledger_entries"),
            )
        for ((table, column, ledger) in drifts) {
            val key = if (table ==
                "money_accounts"
            ) {
                "money_account_id"
            } else {
                "${table.dropLast(1)}_id"
            }
            assertRepaired("$table.$column corrido", setOf(CandidateCheck.HEADER_BALANCES)) {
                val id = firstId(table, "id IN (SELECT $key FROM $ledger)")
                withoutGuards(table) {
                    execSQL("UPDATE $table SET $column = $column + 11 WHERE id = $id")
                }
            }
        }
    }

    @Test
    fun aRepairNeverEditsALedgerOrALine() {
        val candidate = CandidateFixtures.copyOf("ledgers")
        corrupt(candidate) {
            withoutGuards("products") {
                execSQL(
                    "UPDATE products SET on_hand_milli = on_hand_milli + 1, " +
                        "stock_value = stock_value + 1 WHERE stock_mode = 'TRACKED'",
                )
            }
            withoutGuards("sales") { execSQL("UPDATE sales SET total_cost = total_cost + 1") }
            withoutGuards("customers") { execSQL("UPDATE customers SET balance = balance + 1") }
        }
        val before = tablesDigest(candidate, LEDGERS_AND_LINES)

        repairAndAssertClean("varios saldos corridos", candidate)

        assertEquals(before, tablesDigest(candidate, LEDGERS_AND_LINES))
    }

    @Test
    fun aBrokenBalanceChainIsOnlyCountedAndTheLedgerStaysAsItWas() {
        val chains =
            listOf(
                "stock_movements" to "balance_after_milli",
                "customer_ledger_entries" to "balance_after",
                "supplier_ledger_entries" to "balance_after",
                "money_movements" to "balance_after",
                "loyalty_ledger_entries" to "balance_after",
            )
        for ((ledger, column) in chains) {
            assertWarned("cadena de $ledger rota", CandidateCheck.UNDERIVABLE_COHERENCE) {
                withoutGuards(ledger) {
                    updateFirst(ledger, "$column = $column + 1")
                }
            }
        }
    }

    @Test
    fun coherenceThatCannotBeDerivedIsOnlyCounted() {
        val cases: List<Pair<String, SQLiteConnection.() -> Unit>> =
            listOf(
                "línea sin su movimiento" to {
                    execSQL(
                        "DELETE FROM stock_movements WHERE id = " +
                            firstId(
                                "stock_movements",
                                "type = 'SALE' AND id NOT IN " +
                                    "(SELECT reverses_movement_id FROM stock_movements " +
                                    "WHERE reverses_movement_id IS NOT NULL)",
                            ),
                    )
                },
                "venta anulada sin reverso" to {
                    execSQL(
                        "UPDATE sales SET status = 'VOIDED', voided_at = 1, " +
                            "void_reason = 'OTHER' WHERE id = " +
                            firstId("sales", "status = 'ACTIVE'"),
                    )
                },
                "pagos que no suman lo cobrado" to {
                    execSQL(
                        "UPDATE sale_payments SET amount = amount + 1 WHERE id = " +
                            firstId("sale_payments"),
                    )
                },
                "devolución mayor que lo vendido" to {
                    returnBeyondSold(
                        firstId(
                            "sale_lines",
                            "sale_id IN (SELECT id FROM sales WHERE status = 'ACTIVE')",
                        ),
                    )
                },
                "venta anulada con su devolución activa" to {
                    val line = firstId(
                        "sale_lines",
                        "sale_id IN (SELECT id FROM sales WHERE status = 'ACTIVE')",
                    )
                    returnBeyondSold(line, extraMilli = -1)
                    execSQL(
                        "UPDATE sales SET status = 'VOIDED', voided_at = 1, void_reason = 'OTHER' " +
                            "WHERE id = (SELECT sale_id FROM sale_lines WHERE id = $line)",
                    )
                },
                "venta sin líneas" to {
                    val sale =
                        firstId("sales", "id NOT IN (SELECT sale_id FROM sale_returns)")
                    val lines = "(SELECT id FROM sale_lines WHERE sale_id = $sale)"
                    execSQL("DELETE FROM sale_line_components WHERE sale_line_id IN $lines")
                    execSQL(
                        "UPDATE stock_movements SET sale_line_id = NULL " +
                            "WHERE sale_line_id IN $lines",
                    )
                    execSQL("DELETE FROM sale_lines WHERE sale_id = $sale")
                },
            )
        for ((case, change) in cases) {
            val candidate = CandidateFixtures.copyOf("coherence")
            corrupt(candidate) {
                // Algunos cambios tocan tablas con guardas propias de solo agregar: se retiran
                // todas mientras dura el cambio.
                val guards = SchemaCatalog.current.triggers
                for (guard in guards) execSQL("DROP TRIGGER ${guard.name}")
                change()
                for (guard in guards) execSQL(guard.sql)
            }
            val result = validate(candidate)
            val verdict = (result as CandidateValidation.Checked).verdict
            assertTrue("$case: $verdict", verdict !is CandidateVerdict.Rejected)
            assertTrue(
                "$case: ${result.findings()}",
                (result.findings()[CandidateCheck.UNDERIVABLE_COHERENCE] ?: 0) > 0,
            )
        }
    }

    @Test
    fun valuesOutOfRangeAreOnlyCounted() {
        assertWarned("precio fuera de rango", CandidateCheck.VALUE_RANGES) {
            withoutGuards("products") {
                updateFirst("products", "sale_price = 2000000000000")
            }
        }
        assertWarned("fecha de negocio que no existe", CandidateCheck.VALUE_RANGES) {
            withoutGuards("expenses") {
                updateFirst("expenses", "business_date = '2026-02-30'")
            }
        }
        assertWarned("instante negativo", CandidateCheck.VALUE_RANGES) {
            withoutGuards("expenses") {
                updateFirst("expenses", "occurred_at = -5")
            }
        }
        assertWarned("nombre demasiado largo", CandidateCheck.VALUE_RANGES) {
            withoutGuards("tags") {
                val longName = "a".repeat(41)
                execSQL(
                    "INSERT INTO tags (name, normalized_name, created_at, updated_at) " +
                        "VALUES ('$longName', 'largo', 1, 1)",
                )
            }
        }
    }

    @Test
    fun tableRulesAreOnlyCounted() {
        assertWarned("suma de foto que no es hexadecimal", CandidateCheck.TABLE_RULES) {
            withoutGuards("media_assets") {
                updateFirst("media_assets", "sha256 = upper(sha256)")
            }
        }
    }

    @Test
    fun anUnknownPaletteGoesBackToTheFirstOneAndIsCounted() {
        val candidate = CandidateFixtures.copyOf("palette")
        corrupt(candidate) {
            withoutGuards("app_settings") {
                execSQL("UPDATE app_settings SET color_palette = 'NEON'")
            }
        }
        assertEquals(
            CandidateValidation.Checked(
                1,
                CandidateVerdict.Restorable(
                    listOf(
                        CandidateFinding(
                            CandidateCheck.PRESENTATION_PREFERENCES,
                            1,
                        ),
                    ),
                ),
            ),
            validate(candidate),
        )

        prepare(candidate, recalculate = false)

        assertClean("paleta devuelta a la inicial", candidate)
        assertEquals(
            listOf("INDIGO_MUSTARD"),
            inspectWithoutRoom(candidate) { it.texts("SELECT color_palette FROM app_settings") },
        )
    }

    @Test
    fun anUnknownPaymentKindBecomesOtherAndThePaymentsStayAsTheyWere() {
        val candidate = CandidateFixtures.copyOf("payment-kind")
        corrupt(candidate) {
            withoutGuards("payment_methods") {
                updateFirst("payment_methods", "kind = 'BARTER'")
            }
            withoutGuards("sale_payments") {
                updateFirst("sale_payments", "method_kind_snapshot = 'BARTER'")
            }
        }
        assertEquals(mapOf(CandidateCheck.OPEN_DATA to 2L), validate(candidate).findings())
        val payments = tablesDigest(candidate, listOf("sale_payments"))

        prepare(candidate, recalculate = false)

        // El método se puede volver a editar; la copia del tipo en cada pago no cambia.
        assertEquals(mapOf(CandidateCheck.OPEN_DATA to 1L), validate(candidate).findings())
        assertEquals(payments, tablesDigest(candidate, listOf("sale_payments")))
        inspectWithoutRoom(candidate) {
            assertEquals(
                0L,
                it.longValue("SELECT COUNT(*) FROM payment_methods WHERE kind = 'BARTER'"),
            )
        }
    }

    @Test
    fun aRepairThatAGuardRejectsIsRefusedAndLeavesTheCandidateAsItWas() {
        val candidate = CandidateFixtures.copyOf("blocked")
        corrupt(candidate) {
            withoutGuards("products") {
                // Un nombre que la guarda de productos no admite: recalcular su stock no pasa.
                val longName = "n".repeat(81)
                updateFirst(
                    "products",
                    "on_hand_milli = on_hand_milli + 1, name = '$longName'",
                    "stock_mode = 'TRACKED'",
                )
            }
        }
        val before = candidate.readBytes()

        val result = prepare(candidate, recalculate = true)

        assertEquals(CandidateValidation.Refused(CandidateRejection.REPAIR_INCOMPLETE), result)
        assertTrue("La transacción se deshizo", before.contentEquals(candidate.readBytes()))
    }

    private fun assertRepaired(
        case: String,
        checks: Set<CandidateCheck>,
        change: SQLiteConnection.() -> Unit,
    ) {
        val candidate = CandidateFixtures.copyOf("repair")
        corrupt(candidate, change)
        val result = validate(candidate)
        val verdict = (result as CandidateValidation.Checked).verdict
        assertTrue("$case: $verdict", verdict is CandidateVerdict.NeedsRepair)
        assertEquals(case, checks, result.findings().keys)
        val before = tablesDigest(candidate, LEDGERS_AND_LINES)

        repairAndAssertClean(case, candidate)

        assertEquals(case, before, tablesDigest(candidate, LEDGERS_AND_LINES))
    }

    /**
     * Agrega, sin pasar por las guardas, una devolución activa de la línea vendida [saleLineId] con
     * lo vendido más [extraMilli]: una milésima de más es devolver más de lo vendido.
     */
    private fun SQLiteConnection.returnBeyondSold(saleLineId: Long, extraMilli: Long = 1) {
        execSQL(
            "INSERT INTO sale_returns (sale_id, resolution, net_refund, tax_refund, total_refund, " +
                "cost_returned, money_account_id, replacement_sale_id, note, occurred_at, " +
                "business_date, created_at, status, voided_at, void_reason, void_note) " +
                "SELECT sale_id, 'REFUND', 0, 0, 0, 0, NULL, NULL, NULL, 1, " +
                "(SELECT business_date FROM sales WHERE id = sale_id), 1, 'ACTIVE', NULL, NULL, " +
                "NULL FROM sale_lines WHERE id = $saleLineId",
        )
        execSQL(
            "INSERT INTO sale_return_lines (sale_return_id, sale_line_id, quantity_milli, " +
                "net_amount, tax_amount, line_total, line_cost, restock) " +
                "SELECT last_insert_rowid(), id, quantity_milli + $extraMilli, 0, 0, 0, 0, 0 " +
                "FROM sale_lines WHERE id = $saleLineId",
        )
    }

    private fun assertWarned(
        case: String,
        check: CandidateCheck,
        change: SQLiteConnection.() -> Unit,
    ) {
        val candidate = CandidateFixtures.copyOf("warning")
        corrupt(candidate, change)
        val result = validate(candidate)
        val verdict = (result as CandidateValidation.Checked).verdict
        assertTrue("$case: $verdict", verdict is CandidateVerdict.Restorable)
        assertTrue("$case: ${result.findings()}", check in result.findings())
        val before = tablesDigest(candidate, LEDGERS_AND_LINES)

        // Recalcular no cambia un aviso, ni toca el libro.
        val after = prepare(candidate, recalculate = true)

        assertEquals(case, result, after)
        assertEquals(case, before, tablesDigest(candidate, LEDGERS_AND_LINES))
    }

    private fun repairAndAssertClean(case: String, candidate: File) {
        assertEquals(
            case,
            CandidateValidation.Checked(1, CandidateVerdict.Restorable(emptyList())),
            prepare(candidate, recalculate = true),
        )
    }

    private fun prepare(candidate: File, recalculate: Boolean): CandidateValidation = runBlocking {
        CandidateRepair(Dispatchers.IO).prepare(
            candidate,
            CandidateContext.NEW_COPY,
            recalculate,
            Instant.fromEpochMilliseconds(NOW),
        )
    }

    private companion object {
        const val NOW = 1_793_000_000_000L

        /** Los libros y las líneas, que ninguna reparación toca. */
        val LEDGERS_AND_LINES =
            listOf(
                "stock_movements",
                "customer_ledger_entries",
                "supplier_ledger_entries",
                "money_movements",
                "loyalty_ledger_entries",
                "sale_lines",
                "purchase_lines",
                "sale_payments",
                "sale_line_components",
            )
    }
}
