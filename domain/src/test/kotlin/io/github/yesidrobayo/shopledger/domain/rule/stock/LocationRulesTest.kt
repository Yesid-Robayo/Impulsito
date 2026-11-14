package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationInputError
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que se revisa del nombre de una ubicación, y cuándo la interfaz habla de ubicaciones. */
class LocationRulesTest {
    @Test
    fun `a name is trimmed and normalized without case or accents`() {
        assertEquals(
            LocationCheck.Valid(
                LocationDetails("Almacén Sur", "almacen sur", LocationKind.WAREHOUSE),
            ),
            LocationRules.check(LocationDraft(" Almacén Sur ", LocationKind.WAREHOUSE), false),
        )
    }

    @Test
    fun `a blank name is an error only without a translated name to go back to`() {
        val draft = LocationDraft("   ", LocationKind.STORE)

        assertEquals(
            LocationCheck.Invalid(setOf(LocationInputError.NAME_EMPTY)),
            LocationRules.check(draft, isInitial = false),
        )
        assertEquals(
            LocationCheck.Valid(LocationDetails(null, null, LocationKind.STORE)),
            LocationRules.check(draft, isInitial = true),
        )
    }

    @Test
    fun `forty characters fit and forty one do not, counting an emoji as one`() {
        val forty = "a".repeat(38) + "\uD83D\uDCE6b"

        val fits = LocationRules.check(LocationDraft(forty, LocationKind.OTHER), false)
        assertTrue(fits is LocationCheck.Valid)
        assertEquals(
            LocationCheck.Invalid(setOf(LocationInputError.NAME_TOO_LONG)),
            LocationRules.check(LocationDraft(forty + "c", LocationKind.OTHER), true),
        )
    }

    @Test
    fun `locations are shown only with more than one active`() {
        assertFalse(LocationRules.showsLocations(0))
        assertFalse(LocationRules.showsLocations(1))
        assertTrue(LocationRules.showsLocations(2))
    }
}
