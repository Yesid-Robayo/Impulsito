package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.withWriteTransaction
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.Row
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.row
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

// Apoyo de las pruebas de clientes, de sus libros y de las plantillas. Todos los nombres,
// teléfonos, correos y montos son inventados, y los correos usan example.com.

/** Un cliente con solo su nombre, sin saldos ni lista de precios. */
internal fun customer(name: String, archivedAt: Long? = null): CustomerEntity = CustomerEntity(
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    phone = null,
    email = null,
    notes = null,
    priceListId = null,
    balance = 0,
    pointsBalance = 0,
    anonymizedAt = null,
    archivedAt = archivedAt,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/**
 * Una fila del libro del cliente sin origen, con [balanceAfter] tal cual: cada prueba le pone el
 * origen de su tipo y la cadena que quiere.
 */
internal fun customerEntry(
    customerId: Long,
    type: String,
    amount: Long,
    balanceAfter: Long,
    saleId: Long? = null,
    reversesEntryId: Long? = null,
    occurredAt: Long = CREATED_AT,
): CustomerLedgerEntryEntity = CustomerLedgerEntryEntity(
    customerId = customerId,
    type = type,
    amount = amount,
    balanceAfter = balanceAfter,
    saleId = saleId,
    saleReturnId = null,
    reversesEntryId = reversesEntryId,
    dueDate = null,
    note = null,
    occurredAt = occurredAt,
    businessDate = BUSINESS_DATE,
    createdAt = occurredAt,
)

/** Una fila del libro de puntos, con [balanceAfter] tal cual. */
internal fun loyaltyEntry(
    customerId: Long,
    type: String,
    points: Long,
    balanceAfter: Long,
    saleId: Long? = null,
    reversesEntryId: Long? = null,
): LoyaltyLedgerEntryEntity = LoyaltyLedgerEntryEntity(
    customerId = customerId,
    type = type,
    points = points,
    balanceAfter = balanceAfter,
    saleId = saleId,
    reversesEntryId = reversesEntryId,
    note = null,
    occurredAt = CREATED_AT,
    businessDate = BUSINESS_DATE,
    createdAt = CREATED_AT,
)

/** El recordatorio de pago en [languageTag]. */
internal fun template(
    languageTag: String,
    body: String = "Hola {customer}",
): MessageTemplateEntity = MessageTemplateEntity(
    templateKey = "PAYMENT_REMINDER",
    languageTag = languageTag,
    body = body,
    updatedAt = CREATED_AT,
)

/**
 * Agrega la fila al libro del cliente y deja al cliente con su `balance_after`, en una sola
 * transacción: lo que hará el repositorio del libro.
 */
internal suspend fun AppDatabase.appendCustomerEntry(entry: CustomerLedgerEntryEntity): Long =
    withWriteTransaction {
        val id = customerLedgerEntryDao().insert(entry)
        customerDao().setBalance(entry.customerId, entry.balanceAfter, entry.createdAt)
        id
    }

/** Lo mismo con el libro de puntos y `points_balance`. */
internal suspend fun AppDatabase.appendLoyaltyEntry(entry: LoyaltyLedgerEntryEntity): Long =
    withWriteTransaction {
        val id = loyaltyLedgerEntryDao().insert(entry)
        customerDao().setPointsBalance(entry.customerId, entry.balanceAfter, entry.createdAt)
        id
    }

/** El cliente de [customer], escrito como SQL para las sondas. */
internal fun customerRow(name: String = "ana"): Row = row(
    "name" to name,
    "normalized_name" to name,
    "phone" to null,
    "email" to null,
    "notes" to null,
    "price_list_id" to null,
    "balance" to 0L,
    "points_balance" to 0L,
    "anonymized_at" to null,
    "archived_at" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

/** Un saldo inicial de 5.000 en el libro del cliente, sin origen, escrito como SQL. */
internal fun customerEntryRow(customerId: Long): Row = row(
    "customer_id" to customerId,
    "type" to "OPENING_BALANCE",
    "amount" to 5_000L,
    "balance_after" to 5_000L,
    "sale_id" to null,
    "sale_return_id" to null,
    "reverses_entry_id" to null,
    "due_date" to null,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "created_at" to CREATED_AT,
)

/** Un ajuste de 150 puntos en el libro de puntos, sin venta, escrito como SQL. */
internal fun loyaltyEntryRow(customerId: Long): Row = row(
    "customer_id" to customerId,
    "type" to "ADJUSTMENT",
    "points" to 150L,
    "balance_after" to 150L,
    "sale_id" to null,
    "reverses_entry_id" to null,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "created_at" to CREATED_AT,
)

/** El recordatorio de pago en un idioma, escrito como SQL. */
internal fun messageTemplateRow(languageTag: String = "es"): Row = row(
    "template_key" to "PAYMENT_REMINDER",
    "language_tag" to languageTag,
    "body" to "Hola {customer}, tu saldo es {balance}.",
    "updated_at" to CREATED_AT,
)

/**
 * Las consultas del modelo de datos que comprueban el libro del cliente, tal cual: el saldo de
 * cabecera es la suma del libro, y cada `balance_after` es el anterior más su monto. Cada una debe
 * devolver cero filas.
 */
internal const val CUSTOMER_BALANCE_SQL =
    "SELECT c.id FROM customers c LEFT JOIN (" +
        "SELECT customer_id, SUM(amount) AS total FROM customer_ledger_entries " +
        "GROUP BY customer_id) e ON e.customer_id = c.id " +
        "WHERE c.balance <> COALESCE(e.total, 0)"

internal const val CUSTOMER_CHAIN_SQL =
    "SELECT id FROM (SELECT id, amount, balance_after, " +
        "LAG(balance_after, 1, 0) OVER (PARTITION BY customer_id ORDER BY id) " +
        "AS previous_balance " +
        "FROM customer_ledger_entries) WHERE balance_after <> previous_balance + amount"

/** Las mismas dos consultas para el libro de puntos y `points_balance`, tal cual. */
internal const val POINTS_BALANCE_SQL =
    "SELECT c.id FROM customers c LEFT JOIN (" +
        "SELECT customer_id, SUM(points) AS total FROM loyalty_ledger_entries " +
        "GROUP BY customer_id) e ON e.customer_id = c.id " +
        "WHERE c.points_balance <> COALESCE(e.total, 0)"

internal const val POINTS_CHAIN_SQL =
    "SELECT id FROM (SELECT id, points, balance_after, " +
        "LAG(balance_after, 1, 0) OVER (PARTITION BY customer_id ORDER BY id) " +
        "AS previous_balance " +
        "FROM loyalty_ledger_entries) WHERE balance_after <> previous_balance + points"
