package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de `tax_rates`: cada una rechaza lo que debe, con su mensaje, y deja
 * pasar lo válido. Por SQL, porque un DAO no deja escribir un real, un texto o un nulo donde la
 * entidad pide otra cosa.
 */
@RunWith(AndroidJUnit4::class)
internal class TaxGuardsTest : GuardProbeTest() {
    @Test
    fun aRateKeepsItsLengthsAndItsBasisPoints() = runBlocking {
        val valid = taxRateRow()
        for (broken in listOf(
            valid.with("name" to "T".repeat(41)),
            valid.with("name" to ""),
            valid.with("name" to null),
            valid.with("normalized_name" to "t".repeat(41)),
            valid.with("normalized_name" to ""),
            valid.with("rate_bp" to -1L),
            valid.with("rate_bp" to 10_001L),
            valid.with("rate_bp" to 2_100.5),
            valid.with("rate_bp" to "veintiuno"),
            valid.with("rate_bp" to null),
            valid.with("archived_at" to "ayer"),
            valid.with("created_at" to null),
            valid.with("updated_at" to 1.5),
        )) {
            rejected(TAX_RATES, broken.insertInto("tax_rates"))
        }
        val id = insert("tax_rates", valid.with("name" to "T".repeat(40), "rate_bp" to 10_000L))

        for (change in listOf(
            "rate_bp" to 10_001L,
            "rate_bp" to 0.5,
            "name" to "",
            "normalized_name" to null,
            "archived_at" to "hoy",
        )) {
            rejected(TAX_RATES, update("tax_rates", id, change))
        }
        execute(update("tax_rates", id, "rate_bp" to 0L, "archived_at" to LATER))
        assertEquals(0L, count("SELECT rate_bp FROM tax_rates WHERE id = $id"))
    }

    @Test
    fun aRateNameIsUniqueOnlyAmongActiveOnes() = runBlocking {
        val valid = taxRateRow("tasa reducida")
        val archived = insert("tax_rates", valid.with("archived_at" to LATER))
        // Una archivada no reserva su nombre, y dos vigentes no lo repiten.
        val active = insert("tax_rates", valid)
        rejected(TAX_RATES, valid.insertInto("tax_rates"))

        // La archivada no se reactiva con el nombre de una vigente, y otra no se renombra a él.
        rejected(TAX_RATES, update("tax_rates", archived, "archived_at" to null))
        val other = insert("tax_rates", taxRateRow("tasa cero"))
        rejected(TAX_RATES, update("tax_rates", other, "normalized_name" to "tasa reducida"))
        execute(update("tax_rates", active, "archived_at" to LATER))
        execute(update("tax_rates", archived, "archived_at" to null))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM tax_rates " +
                    "WHERE normalized_name = 'tasa reducida' AND archived_at IS NULL",
            ),
        )
    }

    private fun taxRateRow(name: String = "tasa general"): Row = row(
        "name" to name,
        "normalized_name" to name,
        "rate_bp" to 2_100L,
        "archived_at" to null,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private companion object {
        const val TAX_RATES = "guard:tax_rates"
    }
}
