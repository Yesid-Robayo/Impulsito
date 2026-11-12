package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeExpenseRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.testing.seedExpenseCategories
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek

/**
 * Un negocio de prueba para los gastos: base en memoria con las nueve categorías iniciales, una zona
 * cinco horas al oeste de UTC y una moneda reservada para pruebas, confirmada pero sin bloquear.
 * Ninguna prueba supone un país.
 */
internal class ExpenseWorld(
    now: Instant = NOW,
    zoneId: String = ZONE,
    currencyConfirmed: Boolean = true,
) {
    val database = InMemoryDatabase()
    val transactions = FakeTransactionRunner(database)
    val expenses = FakeExpenseRepository(database)
    val categories = FakeExpenseCategoryRepository(database)
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

    /** El id de cada categoría inicial, por su clave. */
    val seeded: Map<String, Long> = database.seedExpenseCategories(SEEDED_AT)

    fun register() = RegisterExpenseUseCase(transactions, settings, expenses, categories, clock)

    /** Anula con el reloj del negocio, o con [at] si la anulación es otro día. */
    fun void(at: Instant? = null) =
        VoidExpenseUseCase(transactions, expenses, at?.let(::FixedClock) ?: clock)

    fun save() = SaveExpenseCategoryUseCase(transactions, categories, clock)

    fun archive() = ArchiveExpenseCategoryUseCase(transactions, categories, clock)

    fun reactivate() = ReactivateExpenseCategoryUseCase(transactions, categories, clock)

    fun delete() = DeleteExpenseCategoryUseCase(transactions, categories)

    companion object {
        /** Cinco horas al oeste de UTC, sin horario de verano. */
        const val ZONE = "Etc/GMT+5"

        /** Las 10:00 del 15 de marzo de 2026 en la zona de prueba. */
        val NOW: Instant = Instant.parse("2026-03-15T15:00:00Z")

        val SEEDED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
