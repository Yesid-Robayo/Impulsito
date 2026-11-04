package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.customer.customerRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de `payment_methods` y `sale_payments`: cada una rechaza lo que debe,
 * con su mensaje, y deja pasar lo válido. Por SQL, porque un DAO no deja escribir un real, un
 * texto o un nulo donde la entidad pide otra cosa.
 */
@RunWith(AndroidJUnit4::class)
internal class PaymentGuardsTest : GuardProbeTest() {
    private var saleId = 0L
    private var methodId = 0L

    @Before
    fun createSaleAndMethod() = runBlocking {
        saleId = insert("sales", saleRow(priceListId))
        methodId = count("SELECT id FROM payment_methods WHERE system_key = 'CARD'")
    }

    @Test
    fun aMethodKeepsItsKindRangesAndNames() = runBlocking {
        val valid = methodRow()
        for (broken in listOf(
            valid.with("kind" to "CHECK"),
            valid.with("kind" to null),
            valid.with("system_key" to "K".repeat(41)),
            valid.with("name" to "N".repeat(41)),
            valid.with("normalized_name" to ""),
            // Sin clave ni nombre no hay qué mostrar, y nombre y nombre normalizado van juntos.
            valid.with("name" to null, "normalized_name" to null),
            valid.with("normalized_name" to null),
            valid.with("fee_bp" to 10_001L),
            valid.with("fee_bp" to 2.5),
            valid.with("fee_fixed" to -1L),
            valid.with("fee_fixed" to 1_000_000_000_001L),
            valid.with("settlement_days" to 3_651L),
            valid.with("price_adjustment_bp" to -10_001L),
            valid.with("price_adjustment_bp" to 10_001L),
            valid.with("instructions_text" to "I".repeat(501)),
            valid.with("instructions_text" to ""),
            valid.with("sort_order" to 1.5),
            valid.with("sort_order" to null),
            valid.with("archived_at" to "ayer"),
            valid.with("created_at" to null),
        )) {
            rejected(METHODS, broken.insertInto("payment_methods"))
        }
        // Un inicial sin nombre, y uno con los topes.
        insert(
            "payment_methods",
            valid.with("system_key" to "GIFT", "name" to null, "normalized_name" to null),
        )
        val id =
            insert(
                "payment_methods",
                valid.with(
                    "fee_bp" to 10_000L,
                    "fee_fixed" to 1_000_000_000_000L,
                    "settlement_days" to 3_650L,
                    "price_adjustment_bp" to -10_000L,
                    "instructions_text" to "I".repeat(500),
                ),
            )

        rejected(METHODS, update("payment_methods", id, "kind" to "CRYPTO"))
        rejected(METHODS, update("payment_methods", id, "name" to null))
        rejected(METHODS, update("payment_methods", id, "settlement_days" to -1L))
        execute(update("payment_methods", id, "kind" to "OTHER", "archived_at" to LATER))
        assertEquals(8L, count("SELECT COUNT(*) FROM payment_methods"))
    }

    @Test
    fun aMethodNameIsUniqueOnlyAmongActiveOnesAndSeedsWithoutNameNeverCollide() = runBlocking {
        val valid = methodRow("vale")
        val archived = insert("payment_methods", valid.with("archived_at" to LATER))
        // Un archivado no reserva su nombre, y dos vigentes no lo repiten.
        val active = insert("payment_methods", valid)
        rejected(METHODS, valid.insertInto("payment_methods"))
        // Dos iniciales sin nombre no chocan, aunque los dos estén vigentes.
        val unnamed = valid.with("name" to null, "normalized_name" to null)
        insert("payment_methods", unnamed.with("system_key" to "GIFT"))
        insert("payment_methods", unnamed.with("system_key" to "VOUCHER"))

        rejected(METHODS, update("payment_methods", archived, "archived_at" to null))
        val other = insert("payment_methods", methodRow("bono"))
        rejected(
            METHODS,
            update("payment_methods", other, "name" to "Vale", "normalized_name" to "vale"),
        )
        execute(update("payment_methods", active, "archived_at" to LATER))
        execute(update("payment_methods", archived, "archived_at" to null))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM payment_methods " +
                    "WHERE normalized_name = 'vale' AND archived_at IS NULL",
            ),
        )
    }

    @Test
    fun aPaymentKeepsItsAmountsAndItsColumnsTogether() = runBlocking {
        val card = paymentRow()
        val cash =
            card.with(
                "method_kind_snapshot" to "CASH",
                "amount" to 12_800L,
                "fee_amount" to 0L,
                "tendered_amount" to 13_000L,
                "change_amount" to 200L,
            )
        val foreign =
            card.with(
                "tender_currency_code" to "XTS",
                "tender_currency_decimals" to 2L,
                "tender_amount" to 5_000L,
                "fx_rate_micro" to 2_560_000L,
            )
        for (broken in listOf(
            card.with("method_name_snapshot" to ""),
            card.with("method_name_snapshot" to "M".repeat(41)),
            card.with("method_kind_snapshot" to "CHECK"),
            card.with("amount" to -1L),
            card.with("amount" to 1_000_000_000_000_001L),
            card.with("amount" to 12_800.5),
            card.with("fee_amount" to -1L),
            card.with("settlement_date" to "2026-02-30"),
            card.with("settlement_date" to "02/01/2026"),
            card.with("created_at" to "hoy"),
            // Recibido y cambio van juntos, solo en efectivo, y lo recibido menos el cambio es el
            // monto.
            cash.with("change_amount" to null),
            cash.with("change_amount" to 100L),
            cash.with("method_kind_snapshot" to "CARD"),
            // La otra moneda va con sus cuatro columnas, y su código es de tres mayúsculas.
            foreign.with("fx_rate_micro" to null),
            foreign.with("tender_amount" to null),
            foreign.with("tender_currency_decimals" to null),
            foreign.with("tender_currency_code" to "xts"),
            foreign.with("tender_currency_code" to "XT1"),
            foreign.with("tender_currency_decimals" to 5L),
            foreign.with("fx_rate_micro" to 0L),
            // A crédito no va a una cuenta, y la venta necesita cliente.
            card.with("method_kind_snapshot" to "ON_ACCOUNT"),
        )) {
            rejected(PAYMENTS, broken.insertInto("sale_payments"))
        }
        insert("sale_payments", card)
        insert("sale_payments", cash)
        insert("sale_payments", foreign)

        val customerId = insert("customers", customerRow())
        val creditSale = insert("sales", saleRow(priceListId).with("customer_id" to customerId))
        val onAccount = card.with("sale_id" to creditSale, "method_kind_snapshot" to "ON_ACCOUNT")
        rejected(PAYMENTS, onAccount.with("money_account_id" to 2L).insertInto("sale_payments"))
        insert("sale_payments", onAccount)
        assertEquals(4L, count("SELECT COUNT(*) FROM sale_payments"))
    }

    @Test
    fun paymentsOnlyGrow() = runBlocking {
        val card = paymentRow()
        val id = insert("sale_payments", card)

        rejected(APPEND_ONLY, update("sale_payments", id, "amount" to 1L))
        rejected(APPEND_ONLY, update("sale_payments", id, "method_name_snapshot" to "Otra"))
        rejected(APPEND_ONLY, "DELETE FROM sale_payments WHERE id = $id")
        // INSERT OR REPLACE borraría el pago sin pasar por la guarda de borrado: la de inserción
        // rechaza el id que ya existe.
        rejected(
            PAYMENTS,
            card.with("amount" to 1L).withId(id).insertOrReplaceInto("sale_payments"),
        )

        assertEquals(3_001L, count("SELECT amount FROM sale_payments WHERE id = $id"))
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_payments"))
    }

    /** Un método que creó la persona, con nombre y sin clave inicial. */
    private fun methodRow(name: String = "billetera"): Row = row(
        "system_key" to null,
        "name" to name,
        "normalized_name" to name,
        "kind" to "WALLET",
        "fee_bp" to 0L,
        "fee_fixed" to 0L,
        "settlement_days" to 0L,
        "price_adjustment_bp" to 0L,
        "money_account_id" to null,
        "instructions_text" to null,
        "instructions_media_id" to null,
        "sort_order" to 7L,
        "archived_at" to null,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    /** Un pago con tarjeta de 3.001, con su comisión, en la moneda del negocio. */
    private fun paymentRow(): Row = row(
        "sale_id" to saleId,
        "payment_method_id" to methodId,
        "method_name_snapshot" to "Tarjeta",
        "method_kind_snapshot" to "CARD",
        "amount" to 3_001L,
        "fee_amount" to 83L,
        "settlement_date" to BUSINESS_DATE,
        "money_account_id" to null,
        "tendered_amount" to null,
        "change_amount" to null,
        "tender_currency_code" to null,
        "tender_currency_decimals" to null,
        "tender_amount" to null,
        "fx_rate_micro" to null,
        "created_at" to CREATED_AT,
    )

    private companion object {
        const val METHODS = "guard:payment_methods"
        const val PAYMENTS = "guard:sale_payments"
        const val APPEND_ONLY = "guard:sale_payments_append_only"
    }
}
