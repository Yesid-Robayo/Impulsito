package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de `product_prices` y `fx_rates`: cada una rechaza lo que debe, con su
 * mensaje, y deja pasar lo válido. Por SQL, porque un DAO no deja escribir un real, un texto o un
 * nulo donde la entidad pide otra cosa, ni un `INSERT OR REPLACE`.
 */
@RunWith(AndroidJUnit4::class)
internal class PricingGuardsTest : GuardProbeTest() {
    @Test
    fun aListPriceIsAnIntegerInAFixedListOtherThanTheInitial() = runBlocking {
        val valid = priceRow(priceListId)
        val initial = count("SELECT id FROM price_lists WHERE system_key = 'DEFAULT'")
        val relative =
            insert(
                "price_lists",
                priceListRow("relativa").with(
                    "kind" to "RELATIVE",
                    "base_price_list_id" to priceListId,
                    "adjustment_bp" to 500L,
                ),
            )
        for (broken in listOf(
            valid.with("price" to 2_300.5),
            valid.with("price" to "caro"),
            valid.with("price" to null),
            valid.with("price" to -1L),
            valid.with("price" to 1_000_000_000_001L),
            valid.with("created_at" to "hoy"),
            valid.with("updated_at" to null),
            valid.with("price_list_id" to initial),
            valid.with("price_list_id" to relative),
        )) {
            rejected(PRICES, broken.insertInto("product_prices"))
        }
        val id = insert("product_prices", valid.with("price" to 1_000_000_000_000L))

        rejected(PRICES, update("product_prices", id, "price" to 0.5))
        rejected(PRICES, update("product_prices", id, "price_list_id" to initial))
        execute(update("product_prices", id, "price" to 0L, "updated_at" to LATER))
        assertEquals(0L, count("SELECT price FROM product_prices WHERE id = $id"))
    }

    @Test
    fun aRateIsAboveZeroAndTheLedgerOnlyGrows() = runBlocking {
        val valid = rateRow()
        for (broken in listOf(
            valid.with("rate_micro" to 0L),
            valid.with("rate_micro" to 2_560_000.5),
            valid.with("rate_micro" to "alta"),
            valid.with("rate_micro" to null),
            valid.with("currency_code" to "XT"),
            valid.with("currency_code" to "X1S"),
            valid.with("note" to "N".repeat(201)),
            valid.with("occurred_at" to "ayer"),
            valid.with("business_date" to "2026-02-30"),
            valid.with("created_at" to null),
        )) {
            rejected(RATES, broken.insertInto("fx_rates"))
        }
        val id = insert("fx_rates", valid.with("note" to "N".repeat(200)))

        rejected(APPEND_ONLY, update("fx_rates", id, "rate_micro" to 1L))
        rejected(APPEND_ONLY, "DELETE FROM fx_rates WHERE id = $id")
        // INSERT OR REPLACE borraría la cotización sin pasar por la guarda de borrado: la de
        // inserción rechaza el id que ya existe.
        rejected(RATES, valid.with("rate_micro" to 1L).withId(id).insertOrReplaceInto("fx_rates"))
        assertEquals(2_560_000L, count("SELECT rate_micro FROM fx_rates WHERE id = $id"))
    }

    private fun priceRow(priceListId: Long): Row = row(
        "price_list_id" to priceListId,
        "product_id" to productId,
        "price" to 2_300L,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    /** Una cotización de la moneda que ISO 4217 reserva para pruebas. */
    private fun rateRow(): Row = row(
        "currency_code" to "XTS",
        "rate_micro" to 2_560_000L,
        "note" to null,
        "occurred_at" to CREATED_AT,
        "business_date" to BUSINESS_DATE,
        "created_at" to CREATED_AT,
    )

    private companion object {
        const val PRICES = "guard:product_prices"
        const val RATES = "guard:fx_rates"
        const val APPEND_ONLY = "guard:fx_rates_append_only"
    }
}
