package io.github.yesidrobayo.shopledger.domain.model.stock

import kotlin.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Una ubicación guardada: con qué se muestra, si es la inicial y si está archivada. */
class LocationTest {
    private val at = Instant.fromEpochMilliseconds(1_000L)

    private fun location(systemKey: String?, name: String?, archivedAt: Instant? = null) = Location(
        id = 1L,
        systemKey = systemKey,
        details = LocationDetails(name, name?.lowercase(), LocationKind.STORE),
        archivedAt = archivedAt,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `the initial location may have no name of its own`() {
        val initial = location(systemKey = "MAIN", name = null)

        assertTrue(initial.isInitial)
        assertFalse(initial.isArchived)
    }

    @Test
    fun `a location created by the person is not initial and can be archived`() {
        val stall = location(systemKey = null, name = "Puesto", archivedAt = at)

        assertFalse(stall.isInitial)
        assertTrue(stall.isArchived)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a location without a name or a key has nothing to be shown with`() {
        location(systemKey = null, name = null)
    }

    @Test
    fun `the overview shows locations only with more than one active`() {
        val main = LocationSummary(location("MAIN", null), 0)
        val one = LocationsOverview(listOf(main), emptyList())
        val two = LocationsOverview(
            listOf(
                LocationSummary(location("MAIN", null), 0),
                LocationSummary(location(null, "Bodega"), 3),
            ),
            emptyList(),
        )

        assertFalse(one.showsLocations)
        assertTrue(two.showsLocations)
    }
}
