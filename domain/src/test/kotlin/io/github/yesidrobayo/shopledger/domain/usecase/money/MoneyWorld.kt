package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeMoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeMoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.testing.seedCashAccount
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek

/**
 * Un negocio de prueba para las cuentas de dinero: base en memoria con la cuenta inicial de efectivo,
 * una zona cinco horas al oeste de UTC y una moneda reservada para pruebas, de dos decimales,
 * confirmada pero sin bloquear. Ninguna prueba supone un país.
 */
internal class MoneyWorld(
    now: Instant = NOW,
    zoneId: String = ZONE,
    currencyConfirmed: Boolean = true,
) {
    val database = InMemoryDatabase()
    val transactions = FakeTransactionRunner(database)
    val accounts = FakeMoneyAccountRepository(database)
    val ledger = FakeMoneyLedgerRepository(database)
    val writer = MoneyLedgerWriter(accounts, ledger)
    val clock = FixedClock(now)
    val settings = FakeAppSettingsRepository(
        appSettings(
            regionalSettings(zoneId, DayOfWeek.MONDAY).copy(
                currency = BusinessCurrency(
                    code = if (currencyConfirmed) BOOK.code else CurrencyCode.UNCONFIRMED,
                    decimals = BOOK.decimals,
                    symbol = null,
                    lockedAt = null,
                ),
            ),
        ),
    )

    /** La cuenta inicial de efectivo, en la moneda del negocio. */
    val cashId: Long = database.seedCashAccount(BOOK, SEEDED_AT)

    fun save() = SaveMoneyAccountUseCase(transactions, settings, accounts, writer, clock)

    fun archive() = ArchiveMoneyAccountUseCase(transactions, accounts, clock)

    fun reactivate() = ReactivateMoneyAccountUseCase(transactions, accounts, clock)

    fun void() = VoidMoneyMovementUseCase(transactions, settings, ledger, writer, clock)

    fun overview() = ObserveMoneyAccountsUseCase(settings, accounts)

    fun movements() = ObserveMoneyMovementsUseCase(ledger)

    companion object {
        /** Cinco horas al oeste de UTC, sin horario de verano. */
        const val ZONE = "Etc/GMT+5"

        /** Las 10:00 del 15 de marzo de 2026 en la zona de prueba. */
        val NOW: Instant = Instant.parse("2026-03-15T15:00:00Z")

        val SEEDED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /** La moneda del negocio: el código de ISO reservado para pruebas, con dos decimales. */
        val BOOK = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 2)

        /** Otra moneda, sin decimales, para una cuenta que no es la del negocio. */
        val OTHER = AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 0)
    }
}
