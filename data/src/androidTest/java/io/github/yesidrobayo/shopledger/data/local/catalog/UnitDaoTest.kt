package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.purchaseLine
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las unidades: las doce iniciales que nacen con la base, sin nombre ni símbolo, junto a las de la
 * persona; clave y nombre únicos; orden por creación; archivo y reactivación; la unidad base por
 * defecto, que nunca se archiva ni se borra, y una unidad en uso, que no se borra.
 *
 * El producto de la base de estas pruebas usa la unidad `UNIT`, como todo producto nuevo.
 */
@RunWith(AndroidJUnit4::class)
internal class UnitDaoTest : LedgerDatabaseTest() {
    private val units get() = database.unitDao()
    private val products get() = database.productDao()

    @Test
    fun aNewDatabaseHasTheTwelveInitialUnitsWithoutVisibleTexts() = runBlocking {
        val initial = units.observeActive().first()

        assertEquals(INITIAL_KEYS, initial.map { it.systemKey })
        assertEquals((1L..12L).toList(), initial.map { it.id })
        // Sin nombre ni símbolo: el texto visible sale de los recursos de su clave, en el idioma de
        // la interfaz. Todas nacen vigentes, con el instante del reloj.
        for (unit in initial) {
            assertNull(unit.name)
            assertNull(unit.normalizedName)
            assertNull(unit.symbol)
            assertNull(unit.archivedAt)
            assertEquals(CREATED_AT, unit.createdAt)
            assertEquals(CREATED_AT, unit.updatedAt)
        }
        assertEquals(INITIAL_KINDS, initial.map { it.kind })
        // Las de conteo no admiten decimales.
        assertEquals(
            listOf("UNIT", "BOX", "PACK", "DOZEN"),
            initial.filter { it.allowsFraction == 0L }.map { it.systemKey },
        )
        assertEquals(UNIT_ID, units.findBySystemKey("UNIT")?.id)
        assertEquals(KILOGRAM_ID, units.findBySystemKey("KILOGRAM")?.id)
        assertEquals(GRAM_ID, units.findBySystemKey("GRAM")?.id)
        assertEquals(BOX_ID, units.findBySystemKey("BOX")?.id)
        assertEquals(emptyList<UnitEntity>(), units.observeArchived().first())
    }

    @Test
    fun anExistingDatabaseDoesNotReceiveTheInitialUnitsAgain() = runBlocking {
        units.update(BOX_ID, "Caja grande", "caja grande", "cj", "COUNT", 0, LATER)
        database.close()

        // Otro reloj: si nacieran otra vez, saldrían con este instante.
        val later = Clock { Instant.fromEpochMilliseconds(MUCH_LATER) }
        database = AppDatabase.open(context, file, Dispatchers.IO, later)

        assertEquals(12L, count(COUNT_SQL))
        assertEquals("caja grande", units.findById(BOX_ID)?.normalizedName)
        assertEquals(CREATED_AT, units.findBySystemKey("DOZEN")?.updatedAt)
    }

    @Test
    fun aPersonUnitReadsBackWithEveryColumn() = runBlocking {
        val own = unit("Bulto de 50", symbol = "bto", kind = "OTHER", allowsFraction = 1)

        val id = units.insert(own)

        assertEquals(own.copy(id = id), units.findById(id))
        assertEquals(id, units.findActiveByNormalizedName("bulto de 50")?.id)
        assertNull(units.findById(MISSING_ID))
        assertNull(units.findBySystemKey("BUNDLE"))
        assertNull(units.findActiveByNormalizedName("bulto"))
    }

    @Test
    fun keysNeverRepeatAndNamesOnlyAmongActiveOnes() = runBlocking {
        constraintFailure(UNIQUE) { units.insert(seededUnit("UNIT")) }

        // Tildes, mayúsculas y espacios de más no hacen otro nombre: la guarda lo rechaza.
        val sack = units.insert(unit("Costal"))
        guardFailure(GUARD) { units.insert(unit("  COSTÁL ")) }
        val roll = units.insert(unit("Rollo"))
        guardFailure(GUARD) { units.update(roll, "Costal", "costal", null, "COUNT", 0, LATER) }

        // Una archivada no reserva su nombre, y no se reactiva si otra vigente ya lo tiene.
        units.setArchivedAt(sack, archivedAt = LATER, updatedAt = LATER)
        assertNull(units.findActiveByNormalizedName("costal"))
        val again = units.insert(unit("costal"))
        assertEquals(again, units.findActiveByNormalizedName("costal")?.id)
        guardFailure(GUARD) { units.setArchivedAt(sack, archivedAt = null, updatedAt = MUCH_LATER) }

        // Una inicial con nombre propio tampoco lo comparte.
        units.update(GRAM_ID, "Gramo fino", "gramo fino", null, "MASS", 1, LATER)
        guardFailure(GUARD) { units.insert(unit("Gramo fino")) }
        assertEquals("rollo", units.findById(roll)?.normalizedName)
        assertEquals(LATER, units.findById(sack)?.archivedAt)
    }

    @Test
    fun listsFollowCreationOrderAndEmitAgainOnArchiveAndReactivation() = runBlocking {
        val before = units.observeActive().first().map { it.id }
        val zeta = units.insert(unit("Zurrón"))
        val alfa = units.insert(unit("Atado"))

        val active = Emissions(this, units.observeActive().ids { it.id })
        val archived = Emissions(this, units.observeArchived().ids { it.id })
        try {
            active.awaitValue(before + listOf(zeta, alfa))
            archived.awaitValue(emptyList())

            units.setArchivedAt(zeta, archivedAt = LATER, updatedAt = LATER)
            units.setArchivedAt(GRAM_ID, archivedAt = LATER, updatedAt = LATER)
            active.awaitValue(before.filterNot { it == GRAM_ID } + listOf(alfa))
            archived.awaitValue(listOf(GRAM_ID, zeta))

            // Reactivada, vuelve a su lugar de creación.
            units.setArchivedAt(GRAM_ID, archivedAt = null, updatedAt = MUCH_LATER)
            active.awaitValue(before + listOf(alfa))
            archived.awaitValue(listOf(zeta))
        } finally {
            active.stop()
            archived.stop()
        }
        assertEquals(MUCH_LATER, units.findById(GRAM_ID)?.updatedAt)
    }

    @Test
    fun updateChangesWhatThePersonWritesAndKeepsTheRest() = runBlocking {
        units.setArchivedAt(BOX_ID, archivedAt = LATER, updatedAt = LATER)
        val before = units.findById(BOX_ID)

        // La persona renombra una inicial: desde ahí se muestra su nombre, y la clave se queda.
        val changed = units.update(BOX_ID, "Caja", "caja", "cj", "OTHER", 1, MUCH_LATER)

        assertEquals(1, changed)
        assertEquals(
            before?.copy(
                name = "Caja",
                normalizedName = "caja",
                symbol = "cj",
                kind = "OTHER",
                allowsFraction = 1,
                updatedAt = MUCH_LATER,
            ),
            units.findById(BOX_ID),
        )
        assertEquals(0, units.update(MISSING_ID, "Otra", "otra", null, "COUNT", 0, LATER))
    }

    @Test
    fun theDefaultBaseUnitIsNeverArchivedNorDeleted() = runBlocking {
        assertEquals(0, units.setArchivedAt(UNIT_ID, archivedAt = LATER, updatedAt = LATER))
        assertEquals(0, units.delete(UNIT_ID))
        assertNull(units.findById(UNIT_ID)?.archivedAt)

        // Renombrarla sí.
        assertEquals(1, units.update(UNIT_ID, "Pieza", "pieza", "pz", "COUNT", 0, LATER))
        assertEquals("pieza", units.findBySystemKey("UNIT")?.normalizedName)
    }

    @Test
    fun aUnitInUseIsNotDeletedAndAnUnusedOneIs() = runBlocking {
        // Unidad base de un producto, conversión, línea de compra y línea de venta: cada uso
        // basta para que su llave detenga el borrado.
        products.insert(product("Harina").copy(baseUnitId = KILOGRAM_ID))
        database.productUnitDao().insert(conversion(productId, BOX_ID))
        val purchaseId = database.purchaseDao().insert(purchase(supplierId = null))
        database.purchaseLineDao().insert(
            purchaseLine(purchaseId, productId).copy(unitId = PACK_ID),
        )
        val saleId = database.saleDao().insert(sale(priceListId))
        database.saleLineDao().insert(
            saleLine(saleId, productId, priceListId).copy(unitId = DOZEN_ID),
        )

        for (used in listOf(UNIT_ID, KILOGRAM_ID, BOX_ID, PACK_ID, DOZEN_ID)) {
            assertTrue("$used está en uso", units.isInUse(used))
        }
        for (used in listOf(KILOGRAM_ID, BOX_ID, PACK_ID, DOZEN_ID)) {
            constraintFailure(FOREIGN_KEY) { units.delete(used) }
        }
        assertEquals(12L, count(COUNT_SQL))

        // Sin uso, una inicial o una de la persona se borran.
        val own = units.insert(unit("Atado"))
        assertFalse(units.isInUse(GRAM_ID))
        assertFalse(units.isInUse(own))
        assertEquals(1, units.delete(GRAM_ID))
        assertEquals(1, units.delete(own))
        assertEquals(0, units.delete(MISSING_ID))
        assertNull(units.findById(GRAM_ID))
    }

    @Test
    fun aProductNeedsAnExistingBaseUnit() = runBlocking {
        constraintFailure(FOREIGN_KEY) {
            products.insert(product("Huérfano").copy(baseUnitId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            products.updateDetails(
                productId,
                name = "Café",
                normalizedName = TextNormalizer.normalize("Café"),
                categoryId = null,
                defaultSupplierId = null,
                baseUnitId = MISSING_ID,
                stockMode = "TRACKED",
                tracksLots = 0,
                fixedUnitCost = 0,
                minStockMilli = 0,
                barcode = null,
                parentProductId = null,
                variantAxis1 = null,
                variantAxis2 = null,
                variantValue1 = null,
                variantValue2 = null,
                photoMediaId = null,
                taxRateId = null,
                targetMarginBp = null,
                referenceCost = null,
                referencePrice = null,
                expiryNoticeDays = null,
                updatedAt = LATER,
            )
        }
        assertEquals(UNIT_ID, products.findById(productId)?.baseUnitId)
    }

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "index_units_normalized_name (normalized_name)",
                "unique index_units_system_key (system_key)",
            ),
            database.indexesOf("units"),
        )
        assertEquals(emptyList<String>(), database.foreignKeysOf("units"))
        assertEquals(
            listOf(
                "index_product_units_barcode (barcode)",
                "unique index_product_units_product_id_unit_id (product_id, unit_id)",
                "index_product_units_unit_id (unit_id)",
            ),
            database.indexesOf("product_units"),
        )
        assertEquals(
            listOf("product_id -> products CASCADE", "unit_id -> units RESTRICT"),
            database.foreignKeysOf("product_units"),
        )
        assertEquals(
            listOf(
                "base_unit_id -> units RESTRICT",
                "category_id -> categories SET NULL",
                "default_supplier_id -> suppliers SET NULL",
                "parent_product_id -> products RESTRICT",
                "photo_media_id -> media_assets SET NULL",
                "tax_rate_id -> tax_rates SET NULL",
            ),
            database.foreignKeysOf("products"),
        )
    }

    private companion object {
        const val MISSING_ID = 999L
        const val GUARD = "guard:units"
        const val COUNT_SQL = "SELECT COUNT(*) FROM units"

        val INITIAL_KEYS =
            listOf(
                "UNIT",
                "KILOGRAM",
                "GRAM",
                "LITER",
                "MILLILITER",
                "POUND",
                "OUNCE",
                "GALLON",
                "METER",
                "BOX",
                "PACK",
                "DOZEN",
            )

        val INITIAL_KINDS =
            listOf(
                "COUNT",
                "MASS",
                "MASS",
                "VOLUME",
                "VOLUME",
                "MASS",
                "MASS",
                "VOLUME",
                "LENGTH",
                "COUNT",
                "COUNT",
                "COUNT",
            )
    }
}

/** Una unidad de la persona, con nombre y sin clave inicial. */
internal fun unit(
    name: String,
    symbol: String? = null,
    kind: String = "COUNT",
    allowsFraction: Long = 0,
): UnitEntity = UnitEntity(
    systemKey = null,
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    symbol = symbol,
    kind = kind,
    allowsFraction = allowsFraction,
    archivedAt = null,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Una unidad con clave inicial y sin nombre, como las que nacen con la base. */
internal fun seededUnit(systemKey: String): UnitEntity = UnitEntity(
    systemKey = systemKey,
    name = null,
    normalizedName = null,
    symbol = null,
    kind = "COUNT",
    allowsFraction = 0,
    archivedAt = null,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** La conversión de una caja de 24 unidades base, a la venta y a la compra, sin código ni precio. */
internal fun conversion(productId: Long, unitId: Long, barcode: String? = null): ProductUnitEntity =
    ProductUnitEntity(
        productId = productId,
        unitId = unitId,
        factorMilli = 24_000,
        salePrice = null,
        barcode = barcode,
        forSale = 1,
        forPurchase = 1,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )
