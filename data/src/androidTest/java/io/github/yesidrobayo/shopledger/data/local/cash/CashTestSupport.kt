package io.github.yesidrobayo.shopledger.data.local.cash

import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.Row
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.row

// Apoyo de las pruebas de caja. Todos los valores y montos son inventados, y las denominaciones no
// son las de ninguna moneda real.

/** Una sesión recién abierta sobre la cuenta, con 20.000 al abrir. */
internal fun openSession(accountId: Long, openingAmount: Long = 20_000): CashSessionEntity =
    CashSessionEntity(
        moneyAccountId = accountId,
        status = "OPEN",
        openingAmount = openingAmount,
        expectedAmount = null,
        countedAmount = null,
        differenceAmount = null,
        openedAt = CREATED_AT,
        closedAt = null,
        businessDate = BUSINESS_DATE,
        note = null,
    )

/** Una denominación activa de [value] en la unidad menor. */
internal fun denomination(value: Long, sortOrder: Long = 0): CashDenominationEntity =
    CashDenominationEntity(
        value = value,
        isActive = 1,
        sortOrder = sortOrder,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

/** La sesión de [openSession], escrita como SQL para las sondas. */
internal fun cashSessionRow(accountId: Long): Row = row(
    "money_account_id" to accountId,
    "status" to "OPEN",
    "opening_amount" to 20_000L,
    "expected_amount" to null,
    "counted_amount" to null,
    "difference_amount" to null,
    "opened_at" to CREATED_AT,
    "closed_at" to null,
    "business_date" to BUSINESS_DATE,
    "note" to null,
)

/** Una denominación de 1.000, escrita como SQL. */
internal fun denominationRow(): Row = row(
    "value" to 1_000L,
    "is_active" to 1L,
    "sort_order" to 0L,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

/** Tres piezas de la denominación al cerrar la sesión, escritas como SQL. */
internal fun countRow(sessionId: Long, denominationId: Long): Row = row(
    "cash_session_id" to sessionId,
    "phase" to "CLOSING",
    "denomination_id" to denominationId,
    "count" to 3L,
)
