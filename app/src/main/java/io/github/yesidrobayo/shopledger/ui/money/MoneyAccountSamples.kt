package io.github.yesidrobayo.shopledger.ui.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyStatement
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import kotlin.time.Instant

// Cuentas y movimientos inventados para las vistas previas y las capturas. La moneda del negocio es
// la de las vistas previas, `XTS` sin decimales; la otra es un código reservado por la norma, con dos.

private val SAMPLE_AT: Instant = Instant.parse("2026-11-13T15:20:00Z")

internal val SAMPLE_TODAY: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-13"))

internal val SAMPLE_ZONE: BusinessTimeZone = requireNotNull(BusinessTimeZone.of("Etc/UTC"))

internal val SAMPLE_BOOK = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 0)

private val SAMPLE_OTHER = AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 2)

private fun sampleAccount(
    id: Long,
    name: String?,
    kind: MoneyAccountKind,
    balance: Long,
    currency: AccountCurrency = SAMPLE_BOOK,
    archived: Boolean = false,
) = MoneyAccount(
    id = id,
    systemKey = if (name == null) "CASH" else null,
    details = MoneyAccountDetails(name, name?.lowercase(), kind),
    currency = currency,
    balance = Money(balance),
    archivedAt = SAMPLE_AT.takeIf { archived },
    createdAt = SAMPLE_AT,
    updatedAt = SAMPLE_AT,
)

/** La cuenta inicial de efectivo y una cuenta de banco: lo que ofrece un formulario que paga. */
internal val SAMPLE_ACCOUNTS: List<MoneyAccount> = listOf(
    sampleAccount(1, null, MoneyAccountKind.CASH, 184_500),
    sampleAccount(2, "Cuenta corriente", MoneyAccountKind.BANK, 950_000),
)

private val SAMPLE_LONG = sampleAccount(
    3,
    PreviewSamples.LONG_NAME,
    MoneyAccountKind.WALLET,
    -PreviewSamples.LONG_AMOUNT.minor,
)

private val SAMPLE_FOREIGN =
    sampleAccount(4, "Reserva", MoneyAccountKind.OTHER, 32_000, SAMPLE_OTHER)

private val SAMPLE_ARCHIVED =
    sampleAccount(5, "Caja chica", MoneyAccountKind.CASH, 0, archived = true)

/** La lista con todas sus filas: por acreditar, saldo negativo largo, otra moneda y archivadas. */
internal val SAMPLE_VIEW = MoneyAccountsView(
    bookCurrency = SAMPLE_BOOK,
    available = Money(184_500 + 950_000),
    accounts = listOf(
        AccountRow(SAMPLE_ACCOUNTS[0], null),
        AccountRow(SAMPLE_ACCOUNTS[1], Money(45_000)),
        AccountRow(SAMPLE_LONG, null),
    ),
    others = listOf(AccountRow(SAMPLE_FOREIGN, null)),
    archived = listOf(AccountRow(SAMPLE_ARCHIVED, null)),
    inUse = true,
)

/** Un negocio que todavía no lleva cuentas: solo la inicial, sin movimientos. */
internal val SAMPLE_EMPTY_VIEW = MoneyAccountsView(
    bookCurrency = SAMPLE_BOOK,
    available = Money.ZERO,
    accounts = listOf(AccountRow(SAMPLE_ACCOUNTS[0].copy(balance = Money.ZERO), null)),
    others = emptyList(),
    archived = emptyList(),
    inUse = false,
)

private fun sampleMovement(
    id: Long,
    type: MoneyMovementType,
    amount: Long,
    after: Long,
    day: BusinessDate,
    origin: MoneyOrigin = MoneyOrigin.None,
    note: String? = null,
    available: BusinessDate? = null,
) = MoneyMovement(
    id,
    MoneyMovementDraft(
        2,
        type,
        Money(amount),
        origin,
        availableDate = available,
        note = note,
    ),
    Money(after),
    RecordTime(SAMPLE_AT, day),
    SAMPLE_AT,
)

/** El libro de la cuenta de banco: saldo inicial, un cobro por acreditar, un retiro anulado. */
internal val SAMPLE_MOVEMENTS: List<MoneyMovement> = listOf(
    sampleMovement(
        1,
        MoneyMovementType.OPENING_BALANCE,
        900_000,
        900_000,
        SAMPLE_TODAY.plusDays(-2),
    ),
    sampleMovement(
        2,
        MoneyMovementType.SALE_RECEIPT,
        46_200,
        946_200,
        SAMPLE_TODAY.plusDays(-1),
        MoneyOrigin.SalePayment(7),
        available = SAMPLE_TODAY.plusDays(1),
    ),
    sampleMovement(
        3,
        MoneyMovementType.FEE,
        -1_200,
        945_000,
        SAMPLE_TODAY.plusDays(-1),
        MoneyOrigin.SalePayment(7),
        available = SAMPLE_TODAY.plusDays(1),
    ),
    sampleMovement(
        4,
        MoneyMovementType.OWNER_DRAW,
        -20_000,
        925_000,
        SAMPLE_TODAY,
        note = "Mercado",
    ),
    sampleMovement(
        5,
        MoneyMovementType.MOVEMENT_VOID,
        20_000,
        945_000,
        SAMPLE_TODAY,
        MoneyOrigin.Void(4),
    ),
    sampleMovement(
        6,
        MoneyMovementType.OWNER_CONTRIBUTION,
        5_000,
        950_000,
        SAMPLE_TODAY,
        note = PreviewSamples.LONG_NAME,
    ),
)

/** La lista lista para mostrar. */
internal val SAMPLE_LIST_STATE = MoneyAccountsUiState(
    content = MoneyAccountsContent.Ready(SAMPLE_VIEW),
    zone = SAMPLE_ZONE,
    today = SAMPLE_TODAY,
)

/** El libro de la cuenta de banco abierto. */
internal val SAMPLE_DETAIL_STATE = SAMPLE_LIST_STATE.copy(
    selectedId = SAMPLE_ACCOUNTS[1].id,
    detail = AccountDetailContent.Ready(
        SAMPLE_VIEW.accounts[1],
        MoneyStatement.byDay(SAMPLE_MOVEMENTS),
        SAMPLE_ZONE,
    ),
)
