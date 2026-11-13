package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.pricing.fixedList
import io.github.yesidrobayo.shopledger.data.local.pricing.productPrice
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReferenceScale
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La masiva ampliada sobre la base real: cada modo guarda su dato en la fila del historial, como pide
 * su guarda, y una masiva sobre la inicial y una fija escribe precio de venta, precio propio y
 * historial con su lista en una sola transacción, que deshacer devuelve. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class BulkPriceListsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val history by lazy { RoomPriceHistoryRepository(database) }
    private val lists by lazy { RoomPriceListRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val apply by lazy {
        ApplyBulkPriceUseCase(
            runner,
            products,
            RoomSystemRowRepository(database),
            history,
            BusinessCalendarSource(settings),
            CurrencyLock(settings),
            Clock { LATER_AT },
            priceLists = lists,
            productPrices = lists,
        )
    }

    private val undo by lazy {
        UndoBulkPriceUseCase(
            runner,
            products,
            RoomSystemRowRepository(database),
            history,
            BusinessCalendarSource(settings),
            Clock { LATER_AT },
            productPrices = lists,
        )
    }

    @Test
    fun twoListsChangeInOneTransactionAndUndoBringsThemBack() = runBlocking {
        val defaultId =
            requireNotNull(RoomSystemRowRepository(database).findId(SystemRow.DEFAULT_PRICE_LIST))
        val (ids, wholesale) = database.withWriteTransaction {
            val ids = (1..2).map { database.productDao().insert(product("Producto inventado $it")) }
            val list = database.priceListDao().insert(fixedList("Mayorista inventada"))
            database.productPriceDao().insert(productPrice(list, ids[0], 2_000))
            ids to list
        }
        val before = changeSeq()
        val request = BulkPriceRequest(
            BulkPriceScope.All,
            BulkPriceAdjustment.Amount(Money(250L)),
            Money.ZERO,
            priceListIds = setOf(defaultId, wholesale),
        )

        val applied = apply(ids, request) as ApplyBulkPriceResult.Applied

        assertEquals(4, applied.changed)
        assertEquals(before + 1, changeSeq())
        // 2500 de venta pasa a 2750; el propio de 2000 a 2250, y el que tomaba el de venta, a 2750.
        assertEquals(
            listOf(Money(2_750L), Money(2_750L)),
            products.findByIds(ids).map {
                it.salePrice
            },
        )
        assertEquals(mapOf(wholesale to Money(2_250L)), lists.findByProduct(ids[0]))
        assertEquals(mapOf(wholesale to Money(2_750L)), lists.findByProduct(ids[1]))
        assertEquals(
            2L,
            count(
                "SELECT COUNT(*) FROM product_price_history WHERE source = 'BULK' " +
                    "AND bulk_mode = 'AMOUNT' AND amount_delta = 250 AND price_list_id = $wholesale",
            ),
        )

        assertEquals(UndoBulkPriceResult.Undone(reverted = 4, kept = 0), undo(applied.batchId))
        assertEquals(
            listOf(Money(2_500L), Money(2_500L)),
            products.findByIds(ids).map {
                it.salePrice
            },
        )
        assertEquals(mapOf(wholesale to Money(2_000L)), lists.findByProduct(ids[0]))
        assertEquals(mapOf(wholesale to Money(2_500L)), lists.findByProduct(ids[1]))
    }

    @Test
    fun everyModeKeepsItsOwnDataInTheHistoryRow() = runBlocking {
        val id = database.withWriteTransaction {
            database.productDao().insert(product("Producto inventado"))
        }
        val modes = listOf(
            BulkPriceAdjustment.Percent(BasisPoints(800L)),
            BulkPriceAdjustment.Amount(Money(-100L)),
            BulkPriceAdjustment.TargetMargin(BasisPoints(3_000L)),
        )
        for (mode in modes) {
            // Sin impuesto y con el costo promedio de 1200 como base del margen objetivo.
            val context = BulkPriceContext(
                pricing = PricingContext(
                    BasisPoints(3_000L),
                    emptyMap(),
                    TaxMode.NONE,
                    null,
                    emptyMap(),
                    Money.ZERO,
                ),
            )
            val result =
                apply(listOf(id), BulkPriceRequest(BulkPriceScope.All, mode, Money(5L)), context)
            assertEquals(ApplyBulkPriceResult.Applied::class, result::class)
        }

        val rows = history.findByProduct(id).filter { it.content.bulkRule != null }
        assertEquals(modes, rows.map { it.content.bulkRule?.adjustment })
    }

    @Test
    fun aRateRepricesFromTheReferencePriceAndKeepsTheRate() = runBlocking {
        // El ejemplo de la regla: 12,50 en una moneda de 2 decimales, cotización 4150,50 y un
        // libro de 0 decimales dan 51881. Los demás productos, sin referencia, no cambian.
        val (withReference, without) = database.withWriteTransaction {
            val with = database.productDao()
                .insert(product("Producto inventado").copy(referencePrice = 1_250L))
            with to database.productDao().insert(product("Otro producto inventado"))
        }
        val before = changeSeq()
        val request = BulkPriceRequest(
            BulkPriceScope.All,
            BulkPriceAdjustment.ReferenceRate(4_150_500_000L),
            Money.ZERO,
        )
        val context = BulkPriceContext(scale = ReferenceScale(currencyDecimals = 0, 2))

        val applied =
            apply(listOf(withReference, without), request, context) as ApplyBulkPriceResult.Applied

        assertEquals(1, applied.changed)
        assertEquals(before + 1, changeSeq())
        assertEquals(
            listOf(Money(51_881L), Money(2_500L)),
            products.findByIds(listOf(withReference, without)).map { it.salePrice },
        )
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM product_price_history WHERE source = 'BULK' " +
                    "AND bulk_mode = 'REFERENCE_RATE' AND fx_rate_micro = 4150500000 " +
                    "AND new_price = 51881",
            ),
        )
    }
}
