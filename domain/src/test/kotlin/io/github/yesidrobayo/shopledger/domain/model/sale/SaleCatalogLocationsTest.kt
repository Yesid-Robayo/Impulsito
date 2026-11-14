package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué ubicaciones ofrece la venta y cuál propone: donde se hizo la última venta, si sigue vigente,
 * o la inicial. Con una sola, ninguna se ofrece.
 */
class SaleCatalogLocationsTest {
    private val main = location(1L, systemKey = "MAIN")
    private val stall = location(2L)
    private val warehouse = location(3L)

    private fun catalog(locations: List<Location>, last: Long? = null) = SaleCatalog(
        products = emptyList(),
        frequent = emptyList(),
        archived = emptyList(),
        units = emptyMap(),
        methods = emptyList(),
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = null,
        weekStartDay = null,
        locations = locations,
        lastSaleLocationId = last,
    )

    @Test
    fun withASingleLocationNothingIsOfferedAndTheSaleGoesThere() {
        val single = catalog(listOf(main))

        assertEquals(emptyList<Location>(), single.saleLocations)
        assertEquals(1L, single.saleLocationId(null))
        // Una elegida que ya no se ofrece no se usa.
        assertEquals(1L, single.saleLocationId(2L))
    }

    @Test
    fun theNextSaleProposesWhereTheLastOneWasMade() {
        val two = catalog(listOf(main, stall, warehouse), last = 2L)

        assertEquals(listOf(main, stall, warehouse), two.saleLocations)
        assertEquals(2L, two.defaultLocationId)
        assertEquals(3L, two.saleLocationId(3L))
        assertEquals(2L, two.saleLocationId(null))
    }

    @Test
    fun aLastLocationThatIsNoLongerActiveFallsBackToTheInitialOne() {
        assertEquals(1L, catalog(listOf(main, stall), last = 9L).defaultLocationId)
        assertEquals(1L, catalog(listOf(stall, main), last = null).defaultLocationId)
    }

    @Test
    fun withoutAnInitialLocationTheFirstOneIsUsedAndWithoutLocationsNone() {
        assertEquals(2L, catalog(listOf(stall, warehouse)).defaultLocationId)
        assertNull(catalog(emptyList()).defaultLocationId)
        assertNull(catalog(emptyList()).saleLocationId(5L))
    }

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(0)

        fun location(id: Long, systemKey: String? = null) = Location(
            id = id,
            systemKey = systemKey,
            details = LocationDetails("Lugar $id", "lugar $id", LocationKind.STORE),
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )
    }
}
