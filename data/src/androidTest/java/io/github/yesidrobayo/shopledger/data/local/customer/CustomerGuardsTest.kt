package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.GuardProbeTest
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.saleRow
import io.github.yesidrobayo.shopledger.data.local.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de clientes, de sus dos libros y de las plantillas de mensaje. Cada
 * guarda rechaza lo que debe, con su mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerGuardsTest : GuardProbeTest() {
    @Test
    fun aCustomerNeedsANameUnlessItWasAnonymized() = runBlocking {
        val valid = customerRow()
        val anonymized =
            valid.with("name" to null, "normalized_name" to null, "anonymized_at" to LATER)
        for (broken in listOf(
            // Sin nombre solo si está anonimizado, y entonces sin ningún dato personal.
            valid.with("name" to null, "normalized_name" to null),
            valid.with("name" to null),
            valid.with("normalized_name" to null),
            anonymized.with("phone" to "+00 555 0101"),
            anonymized.with("email" to "ana@example.com"),
            anonymized.with("notes" to "Prefiere pagar los viernes"),
            anonymized.with("name" to "ana", "normalized_name" to "ana"),
            // Largos, enteros y topes de puntos.
            valid.with("name" to "N".repeat(81)),
            valid.with("normalized_name" to ""),
            valid.with("phone" to "9".repeat(31)),
            valid.with("email" to "e".repeat(121)),
            valid.with("notes" to "N".repeat(501)),
            valid.with("balance" to 1_000.5),
            valid.with("points_balance" to MAX_POINTS + 1),
            valid.with("points_balance" to -MAX_POINTS - 1),
            valid.with("anonymized_at" to "ayer", "name" to null, "normalized_name" to null),
            valid.with("created_at" to "hoy"),
        )) {
            rejected(CUSTOMERS, broken.insertInto("customers"))
        }

        val id =
            insert(
                "customers",
                valid.with(
                    "name" to "N".repeat(80),
                    "phone" to "9".repeat(30),
                    "email" to "e".repeat(120),
                    "notes" to "N".repeat(500),
                    "balance" to -2_000L,
                    "points_balance" to -MAX_POINTS,
                ),
            )
        insert("customers", anonymized.with("points_balance" to MAX_POINTS))
        assertEquals(2L, count("SELECT COUNT(*) FROM customers"))

        rejected(CUSTOMERS, update("customers", id, "name" to null, "normalized_name" to null))
        rejected(CUSTOMERS, update("customers", id, "points_balance" to MAX_POINTS + 1))
        // Anonimizar es borrar lo personal y fijar el instante, todo junto.
        rejected(
            CUSTOMERS,
            update(
                "customers",
                id,
                "name" to null,
                "normalized_name" to null,
                "anonymized_at" to LATER,
            ),
        )
        execute(
            update(
                "customers",
                id,
                "name" to null,
                "normalized_name" to null,
                "phone" to null,
                "email" to null,
                "notes" to null,
                "anonymized_at" to LATER,
            ),
        )
        // Y no se deshace: ni vuelve el nombre, ni se borra ni cambia su instante.
        rejected(CUSTOMERS, update("customers", id, "name" to "ana", "normalized_name" to "ana"))
        rejected(CUSTOMERS, update("customers", id, "anonymized_at" to null))
        rejected(CUSTOMERS, update("customers", id, "anonymized_at" to MUCH_LATER))
        // Sus saldos sí siguen su libro.
        execute(update("customers", id, "balance" to 7_000L, "archived_at" to MUCH_LATER))
        assertEquals(7_000L, count("SELECT balance FROM customers WHERE id = $id"))
    }

    @Test
    fun eachEntryKeepsTheSignAndTheOriginOfItsType() = runBlocking {
        val customerId = insert("customers", customerRow())
        val saleId = insert("sales", saleRow(priceListId))
        val opening = customerEntryRow(customerId)
        val creditSale = opening.with("type" to "CREDIT_SALE", "sale_id" to saleId)
        val payment = opening.with("type" to "PAYMENT", "amount" to -2_000L)
        val returnCredit =
            opening.with("type" to "RETURN_CREDIT", "amount" to -1_000L, "sale_return_id" to 1L)
        for (broken in listOf(
            // Nunca 0, y el signo de cada tipo.
            opening.with("amount" to 0L),
            creditSale.with("amount" to -5_000L),
            payment.with("amount" to 2_000L),
            returnCredit.with("amount" to 1_000L),
            // El origen de cada tipo, y solo el suyo.
            creditSale.with("sale_id" to null),
            opening.with("sale_id" to saleId),
            payment.with("sale_id" to saleId),
            returnCredit.with("sale_return_id" to null),
            opening.with("sale_return_id" to 1L),
            opening.with("reverses_entry_id" to 1L),
            // Un ajuste siempre explica su motivo.
            opening.with("type" to "ADJUSTMENT"),
            opening.with("type" to "ADJUSTMENT", "amount" to -1L),
            // Tipos, enteros, fechas y largos.
            opening.with("type" to "GIFT_CARD"),
            opening.with("amount" to 5_000.5),
            opening.with("balance_after" to "cinco mil"),
            opening.with("due_date" to "2026-02-30"),
            opening.with("business_date" to "2026-1-1"),
            opening.with("note" to "N".repeat(201)),
            opening.with("note" to ""),
            opening.with("occurred_at" to null),
        )) {
            rejected(ENTRIES, broken.insertInto("customer_ledger_entries"))
        }

        for (valid in listOf(
            opening.with("amount" to -5_000L, "balance_after" to -5_000L),
            creditSale.with("due_date" to "2026-02-28"),
            payment,
            returnCredit,
            opening.with("type" to "ADJUSTMENT", "note" to "Corrección"),
            opening.with("type" to "ADJUSTMENT", "amount" to -1L, "note" to "Redondeo"),
        )) {
            insert(ENTRIES_TABLE, valid)
        }
        assertEquals(6L, count("SELECT COUNT(*) FROM customer_ledger_entries"))
    }

    @Test
    fun aCompensationPointsToAnEntryOfTheSameCustomerOnlyOnce() = runBlocking {
        val customerId = insert("customers", customerRow())
        val otherId = insert("customers", customerRow("beatriz"))
        val opening = customerEntryRow(customerId)
        val paymentId =
            insert(ENTRIES_TABLE, opening.with("type" to "PAYMENT", "amount" to -2_000L))
        val otherPaymentId =
            insert(
                "customer_ledger_entries",
                customerEntryRow(otherId).with("type" to "PAYMENT", "amount" to -2_000L),
            )
        val undo =
            opening.with(
                "type" to "ENTRY_VOID",
                "amount" to 2_000L,
                "reverses_entry_id" to paymentId,
            )
        for (broken in listOf(
            // El monto opuesto exacto, del mismo cliente, y de una fila que existe.
            undo.with("amount" to 1_999L),
            undo.with("amount" to -2_000L),
            undo.with("reverses_entry_id" to otherPaymentId),
            undo.with("reverses_entry_id" to MISSING_ID),
            undo.with("reverses_entry_id" to null),
        )) {
            rejected(ENTRIES, broken.insertInto("customer_ledger_entries"))
        }

        val undoId = insert("customer_ledger_entries", undo)
        // La misma fila no se compensa dos veces, y un compensatorio no se compensa.
        rejected(ENTRIES, undo.insertInto("customer_ledger_entries"))
        rejected(
            ENTRIES,
            opening.with("type" to "ENTRY_VOID", "amount" to -2_000L, "reverses_entry_id" to undoId)
                .insertInto("customer_ledger_entries"),
        )
        assertEquals(3L, count("SELECT COUNT(*) FROM customer_ledger_entries"))
    }

    @Test
    fun theCustomerLedgerOnlyGrows() = runBlocking {
        val customerId = insert("customers", customerRow())
        val opening = customerEntryRow(customerId)
        val id = insert("customer_ledger_entries", opening)

        rejected(ENTRIES_APPEND_ONLY, update("customer_ledger_entries", id, "amount" to 6_000L))
        rejected(ENTRIES_APPEND_ONLY, update("customer_ledger_entries", id, "note" to "Corregido"))
        rejected(ENTRIES_APPEND_ONLY, "DELETE FROM customer_ledger_entries WHERE id = $id")
        // INSERT OR REPLACE borraría la fila sin pasar por la guarda de borrado: la de inserción
        // rechaza el id que ya existe.
        rejected(
            ENTRIES,
            opening.with("amount" to 1_000L).withId(id).insertOrReplaceInto(ENTRIES_TABLE),
        )

        assertEquals(5_000L, count("SELECT amount FROM customer_ledger_entries WHERE id = $id"))
        assertEquals(1L, count("SELECT COUNT(*) FROM customer_ledger_entries"))
    }

    @Test
    fun pointsKeepTheSignTheOriginAndTheLimitOfTheirType() = runBlocking {
        val customerId = insert("customers", customerRow())
        val saleId = insert("sales", saleRow(priceListId))
        val adjustment = loyaltyEntryRow(customerId)
        val earn = adjustment.with("type" to "EARN", "points" to 12L, "sale_id" to saleId)
        val redeem = adjustment.with("type" to "REDEEM", "points" to -100L, "sale_id" to saleId)
        for (broken in listOf(
            adjustment.with("points" to 0L),
            earn.with("points" to -12L),
            redeem.with("points" to 100L),
            earn.with("sale_id" to null),
            redeem.with("sale_id" to null),
            adjustment.with("sale_id" to saleId),
            adjustment.with("reverses_entry_id" to 1L),
            adjustment.with("type" to "CASHBACK"),
            adjustment.with("points" to 1.5),
            adjustment.with("points" to MAX_POINTS + 1),
            adjustment.with("balance_after" to -MAX_POINTS - 1),
            adjustment.with("business_date" to "2026-02-29"),
            adjustment.with("note" to "N".repeat(201)),
        )) {
            rejected(LOYALTY, broken.insertInto("loyalty_ledger_entries"))
        }

        insert(
            LOYALTY_TABLE,
            adjustment.with("points" to MAX_POINTS, "balance_after" to MAX_POINTS),
        )
        insert(LOYALTY_TABLE, adjustment.with("points" to -MAX_POINTS, "balance_after" to 0L))
        insert("loyalty_ledger_entries", earn)
        val redeemId = insert("loyalty_ledger_entries", redeem)
        val undo =
            adjustment.with(
                "type" to "ENTRY_VOID",
                "points" to 100L,
                "reverses_entry_id" to redeemId,
            )
        rejected(LOYALTY, undo.with("points" to -100L).insertInto("loyalty_ledger_entries"))
        insert("loyalty_ledger_entries", undo)
        rejected(LOYALTY, undo.insertInto("loyalty_ledger_entries"))
        assertEquals(5L, count("SELECT COUNT(*) FROM loyalty_ledger_entries"))

        rejected(LOYALTY_APPEND_ONLY, update("loyalty_ledger_entries", redeemId, "points" to -50L))
        rejected(LOYALTY_APPEND_ONLY, "DELETE FROM loyalty_ledger_entries WHERE id = $redeemId")
        rejected(
            LOYALTY,
            earn.withId(redeemId).insertOrReplaceInto("loyalty_ledger_entries"),
        )
        assertEquals(-100L, count("SELECT points FROM loyalty_ledger_entries WHERE id = $redeemId"))
    }

    @Test
    fun aTemplateHasAKnownKeyALanguageAndABody() = runBlocking {
        val valid = messageTemplateRow()
        for (broken in listOf(
            valid.with("template_key" to "WELCOME"),
            valid.with("language_tag" to ""),
            valid.with("language_tag" to "es-419-x1"),
            valid.with("body" to ""),
            valid.with("body" to "B".repeat(1_001)),
            valid.with("updated_at" to null),
        )) {
            rejected(TEMPLATES, broken.insertInto("message_templates"))
        }
        val id =
            insert(
                "message_templates",
                valid.with("language_tag" to "pt-BR", "body" to "B".repeat(1_000)),
            )

        rejected(TEMPLATES, update("message_templates", id, "body" to ""))
        rejected(TEMPLATES, update("message_templates", id, "template_key" to "WELCOME"))
        execute(update("message_templates", id, "body" to "Olá {customer}", "updated_at" to LATER))
        assertEquals(1L, count("SELECT COUNT(*) FROM message_templates"))
    }

    private companion object {
        const val MISSING_ID = 999L
        const val ENTRIES_TABLE = "customer_ledger_entries"
        const val LOYALTY_TABLE = "loyalty_ledger_entries"
        const val MAX_POINTS = 1_000_000_000_000L
        const val CUSTOMERS = "guard:customers"
        const val ENTRIES = "guard:customer_ledger_entries"
        const val ENTRIES_APPEND_ONLY = "guard:customer_ledger_entries_append_only"
        const val LOYALTY = "guard:loyalty_ledger_entries"
        const val LOYALTY_APPEND_ONLY = "guard:loyalty_ledger_entries_append_only"
        const val TEMPLATES = "guard:message_templates"
    }
}
