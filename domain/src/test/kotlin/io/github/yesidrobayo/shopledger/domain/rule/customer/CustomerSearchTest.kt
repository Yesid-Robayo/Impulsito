package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buscar clientes por nombre normalizado o por los dígitos del teléfono. */
class CustomerSearchTest {
    private val jose = customer(1L, "José Ríos", phone = "(300) 123-4567")
    private val ana = customer(2L, "Ana Paz", phone = null)
    private val anonymous = customer(3L, null, phone = null)
    private val all = listOf(jose, ana, anonymous)

    @Test
    fun `a blank search keeps everyone, also the anonymized`() {
        assertEquals(all, CustomerSearch.filter(all, "  "))
        assertTrue(CustomerSearch.matches(anonymous, ""))
    }

    @Test
    fun `the name is compared without case or accents`() {
        assertEquals(listOf(jose), CustomerSearch.filter(all, "JOSE rí"))
        assertEquals(listOf(ana), CustomerSearch.filter(all, "paz"))
        assertTrue(CustomerSearch.matches(jose, "rios"))
    }

    @Test
    fun `the phone is compared by its digits only, in any writing system`() {
        assertEquals(listOf(jose), CustomerSearch.filter(all, "300 12"))
        assertEquals(listOf(jose), CustomerSearch.filter(all, "٣٠٠١٢٣"))
        assertTrue(CustomerSearch.matches(jose, "1234567"))
    }

    @Test
    fun `nothing matches the anonymized, a missing phone or other text`() {
        assertFalse(CustomerSearch.matches(anonymous, "ana"))
        assertFalse(CustomerSearch.matches(ana, "300"))
        assertFalse(CustomerSearch.matches(jose, "999"))
        assertFalse(CustomerSearch.matches(jose, "pedro"))
        // Sin dígitos en la búsqueda, el teléfono no cuenta: un guion no encuentra a todos.
        assertFalse(CustomerSearch.matches(jose, "-"))
    }

    private fun customer(id: Long, name: String?, phone: String?): Customer = Customer(
        id = id,
        contact = name?.let {
            CustomerContact(it, TextNormalizer.normalize(it), phone, null, null)
        },
        priceListId = null,
        balance = Money.ZERO,
        pointsBalance = 0L,
        anonymizedAt = if (name == null) AT else null,
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
}
