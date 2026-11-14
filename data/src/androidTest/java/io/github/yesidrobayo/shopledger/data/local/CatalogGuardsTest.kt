package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas del catálogo: categorías, proveedores, productos, historial de precios,
 * listas de precios y categorías de gasto. Cada guarda rechaza lo que debe, con su mensaje, y deja
 * pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class CatalogGuardsTest : GuardProbeTest() {
    @Test
    fun categoriesKeepTheirLengthsAndMargin() = runBlocking {
        val valid = categoryRow()
        for (broken in listOf(
            valid.with("name" to "C".repeat(41)),
            valid.with("name" to null),
            valid.with("normalized_name" to ""),
            valid.with("target_margin_bp" to 9_901L),
            valid.with("target_margin_bp" to 30.5),
            valid.with("created_at" to "hoy"),
        )) {
            rejected(CATEGORIES, broken.insertInto("categories"))
        }
        val id = insert("categories", valid.with("target_margin_bp" to 9_900L))

        rejected(CATEGORIES, update("categories", id, "name" to ""))
        rejected(CATEGORIES, update("categories", id, "target_margin_bp" to -1L))
        execute(update("categories", id, "name" to "C".repeat(40), "target_margin_bp" to null))
        assertEquals(1L, count("SELECT COUNT(*) FROM categories"))
    }

    @Test
    fun aSupplierNameIsUniqueOnlyAmongActiveOnes() = runBlocking {
        val valid = supplierRow("lacteos del valle")
        val archived = insert("suppliers", valid.with("archived_at" to LATER))
        // Una archivada no reserva su nombre.
        val active = insert("suppliers", valid)
        rejected(SUPPLIERS, valid.insertInto("suppliers"))
        for (broken in listOf(
            supplierRow().with("phone" to "3".repeat(31)),
            supplierRow().with("email" to ""),
            supplierRow().with("balance" to 0.5),
            supplierRow().with("payment_terms_days" to 3_651L),
        )) {
            rejected(SUPPLIERS, broken.insertInto("suppliers"))
        }

        // La archivada no se reactiva con el nombre de una vigente, y la vigente sí se edita.
        rejected(SUPPLIERS, update("suppliers", archived, "archived_at" to null))
        execute(update("suppliers", active, "notes" to "Entrega los lunes"))
        execute(update("suppliers", active, "archived_at" to LATER))
        execute(update("suppliers", archived, "archived_at" to null))
        assertEquals(1L, count("SELECT COUNT(*) FROM suppliers WHERE archived_at IS NULL"))
    }

    @Test
    fun aProductKeepsItsModeRulesAndActiveNamesAndCodesDoNotRepeat() = runBlocking {
        val valid = productRow("arroz").with("barcode" to "7701")
        insert("products", valid.with("archived_at" to LATER))
        val rice = insert("products", valid)
        for (broken in listOf(
            // Nombre o código de otro vigente.
            productRow("arroz"),
            productRow("otro").with("barcode" to "7701"),
            // Sin libro de stock no hay saldo ni valor, y solo un producto sin libro tiene costo
            // fijo. Los lotes son solo de un producto con libro.
            productRow("servicio").with("stock_mode" to "UNTRACKED", "on_hand_milli" to 1_000L),
            productRow("combo").with("stock_mode" to "COMPONENTS", "stock_value" to 500L),
            productRow("fijo").with("fixed_unit_cost" to 100L),
            productRow("lotes").with("stock_mode" to "UNTRACKED", "tracks_lots" to 1L),
            productRow("modo").with("stock_mode" to "SERVICE"),
            productRow("real").with("sale_price" to 2_500.5),
            productRow("tope").with("sale_price" to 1_000_000_000_001L),
            productRow("nulo").with("min_stock_milli" to null),
            productRow("eje").with("variant_axis_1" to "E".repeat(41)),
        )) {
            rejected(PRODUCTS, broken.insertInto("products"))
        }
        // Una variante cuelga de un producto que no es variante.
        val variant =
            insert("products", productRow("arroz integral").with("parent_product_id" to rice))
        rejected(
            PRODUCTS,
            productRow(
                "arroz integral fino",
            ).with("parent_product_id" to variant).insertInto("products"),
        )
        val service =
            insert(
                "products",
                productRow(
                    "domicilio",
                ).with("stock_mode" to "UNTRACKED", "fixed_unit_cost" to 900L),
            )
        assertEquals(
            4L,
            count(
                "SELECT COUNT(*) FROM products WHERE id IN ($rice, $variant, $service, $productId)",
            ),
        )
    }

    @Test
    fun withHistoryAProductKeepsItsModeUnitAndLots() = runBlocking {
        val fresh = insert("products", productRow("sal"))
        // Sin historia todo se puede cambiar.
        execute(update("products", fresh, "stock_mode" to "UNTRACKED"))
        execute(update("products", fresh, "stock_mode" to "TRACKED", "base_unit_id" to 2L))
        execute(update("products", fresh, "tracks_lots" to 1L))
        execute(update("products", fresh, "tracks_lots" to 0L))

        // Con una fila del libro, ni el modo, ni la unidad base, ni los lotes.
        execute(movementRow(fresh).insertInto("stock_movements"))
        for (change in listOf(
            "stock_mode" to "UNTRACKED",
            "base_unit_id" to 3L,
            "tracks_lots" to 1L,
        )) {
            rejected(PRODUCTS, update("products", fresh, change))
        }
        // Con una línea de venta, tampoco: el producto de la base ya se vendió.
        insertSaleWithLine()
        rejected(PRODUCTS, update("products", productId, "base_unit_id" to 2L))

        // Lo demás sí cambia, y las reglas de siempre siguen.
        execute(update("products", fresh, "sale_price" to 3_000L, "updated_at" to LATER))
        rejected(PRODUCTS, update("products", fresh, "parent_product_id" to fresh))
        rejected(PRODUCTS, update("products", fresh, "name" to ""))
        insert("products", productRow("pimienta"))
        rejected(PRODUCTS, update("products", fresh, "normalized_name" to "pimienta"))
        assertEquals(3_000L, count("SELECT sale_price FROM products WHERE id = $fresh"))
    }

    @Test
    fun aProductUsageIsOneOfThreeAndChangesEvenWithHistory() = runBlocking {
        for ((index, usage) in listOf("SALE", "INPUT", "BOTH").withIndex()) {
            insert("products", productRow("uso $index").with("usage" to usage))
        }
        for (broken in listOf(
            productRow("otro uso").with("usage" to "SUPPLY"),
            productRow("minusculas").with("usage" to "input"),
            productRow("sin uso").with("usage" to null),
            productRow("numero").with("usage" to 1L),
        )) {
            rejected(PRODUCTS, broken.insertInto("products"))
        }
        // Con historia, el uso sigue cambiando: no es historia, y lo guardado queda como estaba.
        val flour = insert("products", productRow("harina"))
        execute(movementRow(flour).insertInto("stock_movements"))
        execute(update("products", flour, "usage" to "INPUT"))
        execute(update("products", flour, "usage" to "BOTH"))
        rejected(PRODUCTS, update("products", flour, "usage" to "OTHER"))
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM products WHERE id = $flour AND usage = 'BOTH'"),
        )
    }

    @Test
    fun priceHistoryKeepsTheColumnsOfItsSource() = runBlocking {
        val valid = priceChangeRow(productId, priceListId)
        val bulk =
            valid.with(
                "source" to "BULK",
                "batch_id" to BATCH,
                "bulk_mode" to "PERCENT",
                "percent_bp" to 800L,
                "rounding_step" to 50L,
            )
        for (broken in listOf(
            valid.with("source" to "INITIAL"),
            valid.with("old_price" to null),
            valid.with("source" to "GUESS"),
            valid.with("batch_id" to BATCH),
            valid.with("business_date" to "2026-02-30"),
            valid.with("new_price" to 2_700.5),
            bulk.with("batch_id" to null),
            bulk.with("batch_id" to BATCH.drop(1)),
            bulk.with("percent_bp" to null),
            bulk.with("rounding_step" to null),
            bulk.with("amount_delta" to 100L),
        )) {
            rejected(PRICE_HISTORY, broken.insertInto("product_price_history"))
        }

        insert("product_price_history", valid.with("source" to "INITIAL", "old_price" to null))
        insert("product_price_history", valid)
        insert("product_price_history", bulk)
        // Un producto importado nace con su precio de importación, sin precio anterior; uno que ya
        // existía cambia de precio por importación con su precio anterior.
        insert("product_price_history", valid.with("source" to "IMPORT", "old_price" to null))
        insert("product_price_history", valid.with("source" to "IMPORT"))
        assertEquals(5L, count("SELECT COUNT(*) FROM product_price_history"))
    }

    @Test
    fun priceHistoryIsNeverEditedNorDeletedWhileItsProductLives() = runBlocking {
        val id = insert("product_price_history", priceChangeRow(productId, priceListId))

        rejected(PRICE_HISTORY_APPEND_ONLY, update("product_price_history", id, "new_price" to 1L))
        rejected(PRICE_HISTORY_APPEND_ONLY, "DELETE FROM product_price_history WHERE id = $id")

        // Con su producto se va en cascada: el producto, sin libro, sí se borra.
        execute("DELETE FROM products WHERE id = $productId")
        assertEquals(0L, count("SELECT COUNT(*) FROM product_price_history"))
    }

    @Test
    fun priceListsKeepTheirKindAndTheInitialOneStays() = runBlocking {
        val relative =
            priceListRow("minorista").with(
                "kind" to "RELATIVE",
                "base_price_list_id" to priceListId,
                "adjustment_bp" to 1_000L,
            )
        val initial = priceListRow().with(
            "system_key" to "DEFAULT",
            "name" to null,
            "normalized_name" to null,
        )
        for (broken in listOf(
            relative.with("base_price_list_id" to null),
            relative.with("adjustment_bp" to null),
            relative.with("adjustment_bp" to -10_001L),
            priceListRow().with("adjustment_bp" to 500L),
            priceListRow().with("kind" to "TIERED"),
            priceListRow().with("name" to null),
            priceListRow().with("system_key" to null, "name" to null, "normalized_name" to null),
            initial.with("archived_at" to LATER),
        )) {
            rejected(PRICE_LISTS, broken.insertInto("price_lists"))
        }
        val relativeId = insert("price_lists", relative)
        // La inicial nace con la base.
        val initialId = count("SELECT id FROM price_lists WHERE system_key = 'DEFAULT'")

        rejected(PRICE_LISTS, update("price_lists", relativeId, "base_price_list_id" to relativeId))
        rejected(PRICE_LISTS, update("price_lists", initialId, "archived_at" to LATER))
        rejected(PRICE_LISTS, update("price_lists", initialId, "kind" to "RELATIVE"))
        execute(update("price_lists", relativeId, "archived_at" to LATER))

        // La inicial nunca se borra; otra sin uso, sí.
        rejected("guard:price_lists_system", "DELETE FROM price_lists WHERE id = $initialId")
        execute("DELETE FROM price_lists WHERE id = $relativeId")
        assertEquals(0L, count("SELECT COUNT(*) FROM price_lists WHERE id = $relativeId"))
    }

    @Test
    fun anExpenseCategoryNameIsUniqueOnlyAmongActiveOnes() = runBlocking {
        val valid = expenseCategoryRow("empaques")
        val archived = insert("expense_categories", valid.with("archived_at" to LATER))
        // Una archivada no reserva su nombre, y dos vigentes no lo repiten.
        val active = insert("expense_categories", valid)
        rejected(EXPENSE_CATEGORIES, valid.insertInto("expense_categories"))
        for (broken in listOf(
            expenseCategoryRow(name = null),
            expenseCategoryRow("luz").with("normalized_name" to null),
            expenseCategoryRow("agua").with("is_fixed" to 2L),
            expenseCategoryRow("N".repeat(41)),
            expenseCategoryRow(name = null, systemKey = "K".repeat(41)),
        )) {
            rejected(EXPENSE_CATEGORIES, broken.insertInto("expense_categories"))
        }
        // Las iniciales sin nombre no chocan entre sí: la base ya trae nueve, y entra otra.
        insert("expense_categories", expenseCategoryRow(name = null, systemKey = "TEST_SEED"))

        rejected(EXPENSE_CATEGORIES, update("expense_categories", archived, "archived_at" to null))
        val other = insert("expense_categories", expenseCategoryRow("transporte"))
        rejected(
            EXPENSE_CATEGORIES,
            update(
                "expense_categories",
                other,
                "name" to "Empaques",
                "normalized_name" to "empaques",
            ),
        )
        execute(update("expense_categories", active, "archived_at" to LATER))
        execute(update("expense_categories", archived, "archived_at" to null))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM expense_categories " +
                    "WHERE normalized_name = 'empaques' AND archived_at IS NULL",
            ),
        )
    }

    @Test
    fun theOtherExpensesCategoryIsNeverArchivedNorDeleted() = runBlocking {
        // La de otros gastos nace con la base.
        val system = count("SELECT id FROM expense_categories WHERE system_key = 'OTHER'")
        assertEquals(1L, count("SELECT is_system FROM expense_categories WHERE id = $system"))
        val own = insert("expense_categories", expenseCategoryRow("publicidad"))

        rejected(EXPENSE_CATEGORIES, update("expense_categories", system, "archived_at" to LATER))
        rejected(
            "guard:expense_categories_system",
            "DELETE FROM expense_categories WHERE id = $system",
        )
        // Renombrarla sí: desde ahí se muestra el nombre de la persona.
        execute(
            update(
                "expense_categories",
                system,
                "name" to "Varios",
                "normalized_name" to "varios",
            ),
        )
        execute("DELETE FROM expense_categories WHERE id = $own")
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM expense_categories WHERE id IN ($system, $own)"),
        )
    }

    private companion object {
        const val CATEGORIES = "guard:categories"
        const val SUPPLIERS = "guard:suppliers"
        const val PRODUCTS = "guard:products"
        const val PRICE_HISTORY = "guard:product_price_history"
        const val PRICE_HISTORY_APPEND_ONLY = "guard:product_price_history_append_only"
        const val PRICE_LISTS = "guard:price_lists"
        const val EXPENSE_CATEGORIES = "guard:expense_categories"
        const val BATCH = "6f1c2a4e-0000-4000-8000-0000000000aa"
    }
}
