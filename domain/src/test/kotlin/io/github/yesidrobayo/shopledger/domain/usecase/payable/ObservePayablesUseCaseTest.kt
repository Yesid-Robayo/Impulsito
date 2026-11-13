package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.PayablesSource
import io.github.yesidrobayo.shopledger.domain.port.SupplierAccount
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La lista de cuentas por pagar: lo vencido primero, después lo próximo, lo que no vence y los
 * saldos a favor; los totales a mano. Hoy es el 2026-11-02 en la zona del negocio, que va cinco
 * horas detrás de UTC: a las 03:00 UTC del 3 todavía es el 2, y lo que vence el 2 no está vencido.
 */
class ObservePayablesUseCaseTest {
    private val accounts = MutableStateFlow<List<SupplierAccount>>(emptyList())
    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)),
    )

    private fun observe(clockMillis: Long = NOV_2_LATE) = ObservePayablesUseCase(
        PayablesSource { accounts },
        BusinessCalendarSource(settings),
        FixedClock.atEpochMillis(clockMillis),
    )()

    private suspend fun overview(): PayablesOverview =
        (observe().first() as PayablesLoad.Ready).overview

    @Test
    fun `nothing owed gives an empty list with zero totals`() = runTest {
        assertEquals(
            PayablesOverview(nov(2), emptyList(), Money.ZERO, Money.ZERO, 0),
            overview(),
        )
    }

    @Test
    fun `overdue comes first, then upcoming, then without due date, then in favor`() = runTest {
        accounts.value = listOf(
            account(1, "Zeta", charge(1, 4_000, due = nov(20))),
            account(2, "Alfa", charge(2, 3_000, due = null)),
            account(3, "Beta", charge(3, 5_000, due = oct(30)), charge(4, 1_000, due = nov(5))),
            account(4, "Gama", charge(5, 2_000, due = nov(1))),
            account(5, "Delta", payment(6, -1_500)),
            account(6, "Omega", charge(7, 6_000, due = nov(2))),
        )

        val overview = overview()

        assertEquals(listOf(3L, 4L, 6L, 1L, 2L, 5L), overview.rows.map { it.supplier.id })
        assertEquals(
            listOf(
                PayableStatus.OVERDUE,
                PayableStatus.OVERDUE,
                PayableStatus.UPCOMING,
                PayableStatus.UPCOMING,
                PayableStatus.NO_DUE_DATE,
                PayableStatus.IN_FAVOR,
            ),
            overview.rows.map { it.status },
        )
        val beta = overview.rows.first()
        assertEquals(oct(30), beta.overdueSince)
        assertEquals(Money(5_000), beta.overdueAmount)
        assertEquals(nov(5), beta.nextDue)
        assertEquals(Money(6_000), beta.owed)
        assertEquals(Money(1_500), overview.rows.last().creditInFavor)
        // 4000 + 3000 + 6000 + 2000 + 6000.
        assertEquals(Money(21_000), overview.owed)
        // Del 2 al 8: 1000 del 5 y 6000 del 2.
        assertEquals(Money(7_000), overview.dueWithinWeek)
        assertEquals(2, overview.overdueCount)
    }

    @Test
    fun `a due date seven days ahead is outside the week`() = runTest {
        accounts.value = listOf(
            account(1, "Alfa", charge(1, 1_000, due = nov(8)), charge(2, 2_000, due = nov(9))),
        )

        assertEquals(Money(1_000), overview().dueWithinWeek)
    }

    @Test
    fun `ties keep the name order and then the id`() = runTest {
        accounts.value = listOf(
            account(3, "Beta", charge(1, 1_000, due = nov(10))),
            account(2, "Alfa", charge(2, 1_000, due = nov(10))),
            account(1, "Alfa", charge(3, 1_000, due = nov(10))),
        )

        assertEquals(listOf(1L, 2L, 3L), overview().rows.map { it.supplier.id })
    }

    @Test
    fun `an unknown zone has no today`() = runTest {
        settings.settings.value = appSettings(regionalSettings("Invented/Zone", DayOfWeek.MONDAY))

        assertEquals(PayablesLoad.UnknownTimeZone("Invented/Zone"), observe().first())
    }

    @Test
    fun `figures that do not fit report a damaged ledger`() = runTest {
        accounts.value = listOf(account(1, "Alfa", payment(1, Long.MIN_VALUE + 1), payment(2, -2)))

        assertEquals(PayablesLoad.LedgerDamaged, observe().first())
    }

    @Test
    fun `totals that do not fit report a damaged ledger`() = runTest {
        accounts.value = listOf(
            account(1, "Alfa", charge(1, Long.MAX_VALUE, due = null)),
            account(2, "Beta", charge(2, 1, due = null)),
        )

        assertEquals(PayablesLoad.LedgerDamaged, observe().first())
    }

    private fun account(id: Long, name: String, vararg entries: SupplierLedgerEntry) =
        SupplierAccount(
            Supplier(
                id = id,
                details = SupplierDetails(name, name.lowercase(), null, null, null, null),
                balance = Money(entries.sumOf { it.content.amount.minor }),
                archivedAt = null,
                createdAt = AT,
                updatedAt = AT,
            ),
            entries.toList(),
        )

    private fun charge(id: Long, amount: Long, due: BusinessDate?) =
        entry(id, SupplierLedgerEntryType.CREDIT_PURCHASE, amount, due)

    private fun payment(id: Long, amount: Long) =
        entry(id, SupplierLedgerEntryType.PAYMENT, amount, null)

    private fun entry(id: Long, type: SupplierLedgerEntryType, amount: Long, due: BusinessDate?) =
        SupplierLedgerEntry(
            id,
            NewSupplierLedgerEntry(
                supplierId = 1,
                type = type,
                amount = Money(amount),
                balanceAfter = Money(amount),
                dueDate = due,
                occurredAt = AT,
                businessDate = oct(1),
                createdAt = AT,
            ),
        )

    private fun nov(day: Int) = BusinessDate(LocalDate(2026, 11, day))

    private fun oct(day: Int) = BusinessDate(LocalDate(2026, 10, day))

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(0)

        /** 2026-11-03 a las 03:00 UTC: las 22:00 del 2 en la zona del negocio. */
        const val NOV_2_LATE = 1_793_674_800_000L
    }
}
