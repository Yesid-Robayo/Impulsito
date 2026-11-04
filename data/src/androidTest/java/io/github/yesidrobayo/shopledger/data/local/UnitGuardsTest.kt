package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.BOX_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.DOZEN_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.GRAM_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.PACK_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de las unidades y de las conversiones de cada producto, y la condición
 * de las conversiones en las guardas de los productos: cada guarda rechaza lo que debe, con su
 * mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class UnitGuardsTest : GuardProbeTest() {
    @Test
    fun aUnitKeepsItsLengthsKindAndNameOrKey() = runBlocking {
        val valid = unitRow("bulto")
        for (broken in listOf(
            valid.with("name" to "B".repeat(41), "normalized_name" to "b".repeat(41)),
            valid.with("symbol" to "S".repeat(11)),
            valid.with("symbol" to ""),
            valid.with("kind" to "WEIGHT"),
            valid.with("allows_fraction" to 2L),
            valid.with("created_at" to "hoy"),
            valid.with("archived_at" to 1.5),
            // El nombre y su forma normalizada van juntos, y una unidad sin clave tiene nombre.
            valid.with("normalized_name" to null),
            valid.with("name" to null),
            valid.with("name" to null, "normalized_name" to null),
            valid.with("system_key" to "K".repeat(41)),
            // La unidad base por defecto nunca nace archivada.
            valid.with(
                "system_key" to "UNIT",
                "name" to null,
                "normalized_name" to null,
                "archived_at" to LATER,
            ),
        )) {
            rejected(UNITS, broken.insertInto("units"))
        }

        val own = insert("units", valid.with("kind" to "OTHER", "symbol" to "S".repeat(10)))
        // Una unidad con clave puede no tener nombre: se muestra el texto de su clave.
        insert(
            "units",
            unitRow(
                "rollo",
            ).with("system_key" to "ROLL", "name" to null, "normalized_name" to null),
        )
        rejected(UNITS, update("units", own, "name" to ""))
        rejected(UNITS, update("units", own, "kind" to "AREA"))
        execute(update("units", own, "allows_fraction" to 1L, "archived_at" to LATER))
        assertEquals(14L, count("SELECT COUNT(*) FROM units"))
    }

    @Test
    fun aUnitNameIsUniqueOnlyAmongActiveOnes() = runBlocking {
        val valid = unitRow("bulto")
        val archived = insert("units", valid.with("archived_at" to LATER))
        // Una archivada no reserva su nombre, y dos vigentes no lo repiten.
        val active = insert("units", valid)
        rejected(UNITS, valid.insertInto("units"))
        // Las iniciales sin nombre no chocan con nadie.
        insert(
            "units",
            valid.with("system_key" to "SACK", "name" to null, "normalized_name" to null),
        )

        rejected(UNITS, update("units", archived, "archived_at" to null))
        val other = insert("units", unitRow("rollo"))
        rejected(UNITS, update("units", other, "name" to "Bulto", "normalized_name" to "bulto"))
        execute(update("units", active, "archived_at" to LATER))
        execute(update("units", archived, "archived_at" to null))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM units WHERE normalized_name = 'bulto' AND archived_at IS NULL",
            ),
        )
    }

    @Test
    fun theDefaultBaseUnitIsNeverArchivedNorDeleted() = runBlocking {
        rejected(UNITS, update("units", UNIT_ID, "archived_at" to LATER))
        rejected("guard:units_system", "DELETE FROM units WHERE id = $UNIT_ID")

        // Renombrarla sí: desde ahí se muestra el nombre de la persona. Otra inicial sin uso sí se
        // archiva y se borra.
        execute(update("units", UNIT_ID, "name" to "Pieza", "normalized_name" to "pieza"))
        execute(update("units", BOX_ID, "archived_at" to LATER))
        execute("DELETE FROM units WHERE id = $BOX_ID")
        assertEquals(
            1L,
            count("SELECT normalized_name = 'pieza' FROM units WHERE id = $UNIT_ID"),
        )
        assertEquals(0L, count("SELECT COUNT(*) FROM units WHERE id = $BOX_ID"))
    }

    @Test
    fun aConversionHasAPositiveFactorWithinTheLineLimit() = runBlocking {
        val valid = productUnitRow(productId, BOX_ID)
        for (broken in listOf(
            valid.with("factor_milli" to 0L),
            valid.with("factor_milli" to -24_000L),
            valid.with("factor_milli" to 1_000_000_001L),
            valid.with("factor_milli" to 2.5),
            valid.with("factor_milli" to null),
            valid.with("sale_price" to -1L),
            valid.with("sale_price" to 1_000_000_000_001L),
            valid.with("barcode" to ""),
            valid.with("barcode" to "7".repeat(65)),
            valid.with("for_sale" to 2L),
            valid.with("for_purchase" to null),
            valid.with("updated_at" to null),
        )) {
            rejected(PRODUCT_UNITS, broken.insertInto("product_units"))
        }

        // Una milésima de la base es el mínimo: un gramo de un producto en kilogramos.
        val gram = insert("product_units", valid.with("unit_id" to GRAM_ID, "factor_milli" to 1L))
        val box =
            insert(
                "product_units",
                valid.with("factor_milli" to 1_000_000_000L, "sale_price" to 1_000_000_000_000L),
            )
        rejected(PRODUCT_UNITS, update("product_units", gram, "factor_milli" to 0L))
        rejected(PRODUCT_UNITS, update("product_units", box, "for_sale" to -1L))
        execute(update("product_units", box, "factor_milli" to 24_000L, "sale_price" to null))
        assertEquals(2L, count("SELECT COUNT(*) FROM product_units"))
    }

    @Test
    fun aConversionCodeRepeatsNeitherAnotherConversionNorAnotherActiveProduct() = runBlocking {
        val tea = insert("products", productRow("te").with("barcode" to "7701"))
        insert("products", productRow("te viejo").with("barcode" to "7709", "archived_at" to LATER))
        execute(update("products", productId, "barcode" to "7700"))
        val valid = productUnitRow(productId, BOX_ID)

        // El código de otro producto vigente, o el de otra conversión, no se repite.
        rejected(PRODUCT_UNITS, valid.with("barcode" to "7701").insertInto("product_units"))
        val box = insert("product_units", valid.with("barcode" to "7702"))
        rejected(
            PRODUCT_UNITS,
            productUnitRow(tea, BOX_ID).with("barcode" to "7702").insertInto("product_units"),
        )
        // El del mismo producto sí, y el de un archivado también: no reserva el suyo.
        val pack = insert("product_units", valid.with("unit_id" to PACK_ID, "barcode" to "7700"))
        val dozen = insert("product_units", valid.with("unit_id" to DOZEN_ID, "barcode" to "7709"))

        rejected(PRODUCT_UNITS, update("product_units", pack, "barcode" to "7702"))
        rejected(PRODUCT_UNITS, update("product_units", dozen, "barcode" to "7701"))
        // Conservar su propio código no choca consigo misma.
        execute(update("product_units", box, "barcode" to "7702", "factor_milli" to 12_000L))
        assertEquals(3L, count("SELECT COUNT(*) FROM product_units WHERE product_id = $productId"))
    }

    @Test
    fun aProductCodeDoesNotRepeatTheCodeOfAnotherProductsConversion() = runBlocking {
        insert("product_units", productUnitRow(productId, BOX_ID).with("barcode" to "7702"))

        rejected(PRODUCTS, productRow("te").with("barcode" to "7702").insertInto("products"))
        val tea = insert("products", productRow("te"))
        rejected(PRODUCTS, update("products", tea, "barcode" to "7702"))

        // Un archivado no lo reserva, y el producto de la conversión sí puede llevarlo.
        insert(
            "products",
            productRow("te viejo").with("barcode" to "7702", "archived_at" to LATER),
        )
        execute(update("products", productId, "barcode" to "7702"))
        assertEquals(1L, count("SELECT barcode = '7702' FROM products WHERE id = $productId"))
    }

    private companion object {
        const val UNITS = "guard:units"
        const val PRODUCT_UNITS = "guard:product_units"
        const val PRODUCTS = "guard:products"
    }
}
