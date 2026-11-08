package io.github.yesidrobayo.shopledger.domain.model.payment

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un método de pago guardado, y lo que se puede hacer con él. */
class PaymentMethodRecordTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test
    fun `a name always travels with its normalized form`() {
        assertThrows(IllegalArgumentException::class.java) {
            details(name = "Vale", normalizedName = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            details(name = null, normalizedName = "vale")
        }
        assertEquals("vale", details(name = "Vale", normalizedName = "vale").normalizedName)
        assertEquals(null, details(name = null, normalizedName = null).name)
    }

    @Test
    fun `a method without a name needs its initial key`() {
        assertThrows(IllegalArgumentException::class.java) {
            method(systemKey = null, details = details(name = null, normalizedName = null))
        }
        val initial = method(systemKey = "CASH", details = details(null, null))
        val named = method(systemKey = null, details = details("Vale", "vale"))

        assertEquals("CASH", initial.systemKey)
        assertEquals("Vale", named.details.name)
    }

    @Test
    fun `an archived method is the one with an archive instant`() {
        assertFalse(method().isArchived)
        assertTrue(method(archivedAt = at).isArchived)
    }

    @Test
    fun `the last active method is neither archived nor deleted`() {
        val last = PaymentMethodOptions(method(), hasSalePayments = false, activeCount = 1)

        assertTrue(last.isLastActive)
        assertFalse(last.canArchive)
        assertFalse(last.canDelete)
        assertFalse(last.canReactivate)
    }

    @Test
    fun `an active method without sales can be archived or deleted, and its kind changed`() {
        val options = PaymentMethodOptions(method(), hasSalePayments = false, activeCount = 2)

        assertFalse(options.isLastActive)
        assertTrue(options.canArchive)
        assertTrue(options.canDelete)
        assertFalse(options.isKindLocked)
        assertFalse(options.canReactivate)
    }

    @Test
    fun `a method with sales is only archived, and its kind is locked`() {
        val options = PaymentMethodOptions(method(), hasSalePayments = true, activeCount = 3)

        assertTrue(options.canArchive)
        assertFalse(options.canDelete)
        assertTrue(options.isKindLocked)
    }

    @Test
    fun `an archived method is reactivated, and deleted only without sales`() {
        // Con un solo vigente, que no es este, el archivado igual se puede eliminar.
        val archived = method(archivedAt = at)
        val clean = PaymentMethodOptions(archived, hasSalePayments = false, activeCount = 1)
        val used = PaymentMethodOptions(archived, hasSalePayments = true, activeCount = 1)

        assertFalse(clean.isLastActive)
        assertTrue(clean.canReactivate)
        assertFalse(clean.canArchive)
        assertTrue(clean.canDelete)
        assertFalse(used.canDelete)
    }

    private fun details(name: String? = "Vale", normalizedName: String? = "vale") =
        PaymentMethodDetails(
            name = name,
            normalizedName = normalizedName,
            kind = PaymentMethodKind.OTHER,
            feeRate = BasisPoints.ZERO,
            feeFixed = Money.ZERO,
            settlementDays = 0,
            priceAdjustment = BasisPoints.ZERO,
            moneyAccountId = null,
            instructionsText = null,
            instructionsMediaId = null,
        )

    private fun method(
        systemKey: String? = null,
        details: PaymentMethodDetails = details(),
        archivedAt: Instant? = null,
    ) = PaymentMethodRecord(
        id = 7L,
        systemKey = systemKey,
        details = details,
        sortOrder = 1L,
        archivedAt = archivedAt,
        createdAt = at,
        updatedAt = at,
    )
}
