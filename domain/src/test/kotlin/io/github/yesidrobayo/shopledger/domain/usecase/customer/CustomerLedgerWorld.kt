package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeCustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeCustomerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeMoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeMoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek

/**
 * Clientes y su libro sobre la base en memoria, con una moneda de prueba y la zona [zoneId]. Sin
 * moneda confirmada, ningún monto se guarda.
 */
internal class CustomerLedgerWorld(
    zoneId: String = "UTC",
    currencyConfirmed: Boolean = true,
    now: Instant = NOW,
) {
    val database = InMemoryDatabase()
    val transactions = FakeTransactionRunner(database)
    val customers = FakeCustomerRepository(database)
    val ledger = FakeCustomerLedgerRepository(database)
    val accounts = FakeMoneyAccountRepository(database)
    val moneyLedger = FakeMoneyLedgerRepository(database)
    val moneyWriter = MoneyLedgerWriter(accounts, moneyLedger)
    val clock = FixedClock(now)
    val settings = FakeAppSettingsRepository(
        appSettings(
            regionalSettings(zoneId, DayOfWeek.MONDAY).let { regional ->
                regional.copy(
                    currency = BusinessCurrency(
                        code = if (currencyConfirmed) {
                            requireNotNull(CurrencyCode.of("XTS"))
                        } else {
                            CurrencyCode.UNCONFIRMED
                        },
                        decimals = 0,
                        symbol = null,
                        lockedAt = null,
                    ),
                )
            },
        ),
    )

    /** Un cliente vigente sin saldo, con el id [id]. */
    fun seedCustomer(id: Long, name: String = "Cliente $id") {
        val customer = Customer(
            id = id,
            contact = CustomerContact(name, name.lowercase(), null, null, null),
            priceListId = null,
            balance = Money.ZERO,
            pointsBalance = 0L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = NOW,
            updatedAt = NOW,
        )
        database.seed {
            it.copy(
                customers = it.customers + (id to customer),
                lastIds = it.lastIds + ("customers" to maxOf(id, it.lastIds["customers"] ?: 0L)),
            )
        }
    }

    companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_762_000_000_000L)
    }
}
