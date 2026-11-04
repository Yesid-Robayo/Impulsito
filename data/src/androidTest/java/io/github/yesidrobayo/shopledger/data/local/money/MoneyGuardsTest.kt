package io.github.yesidrobayo.shopledger.data.local.money

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.GuardProbeTest
import io.github.yesidrobayo.shopledger.data.local.cash.cashSessionRow
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.payable.supplierEntryRow
import io.github.yesidrobayo.shopledger.data.local.payment.insertSaleWithCardPayment
import io.github.yesidrobayo.shopledger.data.local.purchaseReturn
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.local.saleRow
import io.github.yesidrobayo.shopledger.data.local.supplierRow
import io.github.yesidrobayo.shopledger.data.local.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de cuentas y movimientos de dinero. Cada guarda rechaza lo que debe,
 * con su mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class MoneyGuardsTest : GuardProbeTest() {
    @Test
    fun anAccountHasAKnownKindAValidCurrencyAndAName() = runBlocking {
        val valid = moneyAccountRow()
        for (broken in listOf(
            valid.with("kind" to "CRYPTO"),
            valid.with("currency_code" to "xts"),
            valid.with("currency_code" to "XT"),
            valid.with("currency_code" to "XT1"),
            valid.with("currency_code" to "XTSS"),
            valid.with("currency_decimals" to 5L),
            valid.with("currency_decimals" to -1L),
            valid.with("balance" to 10.5),
            // Sin clave inicial, el nombre es obligatorio, y siempre junto con el normalizado.
            valid.with("name" to null, "normalized_name" to null),
            valid.with("normalized_name" to null),
            valid.with("name" to "N".repeat(41)),
            valid.with("system_key" to ""),
            valid.with("archived_at" to "ayer"),
        )) {
            rejected(ACCOUNTS, broken.insertInto("money_accounts"))
        }
        val id =
            insert(
                "money_accounts",
                valid.with("name" to "N".repeat(40), "normalized_name" to "n".repeat(40)),
            )
        // El nombre no se repite entre vigentes, y una archivada no lo reserva.
        val archived = insert("money_accounts", valid.with("archived_at" to LATER))
        insert("money_accounts", valid)
        rejected(ACCOUNTS, valid.insertInto("money_accounts"))
        rejected(ACCOUNTS, update("money_accounts", archived, "archived_at" to null))
        insert(
            "money_accounts",
            valid.with("system_key" to "SAVINGS", "name" to null, "normalized_name" to null),
        )

        rejected(ACCOUNTS, update("money_accounts", id, "currency_code" to "ab1"))
        rejected(ACCOUNTS, update("money_accounts", id, "name" to null, "normalized_name" to null))
        // Sin movimientos, la moneda todavía cambia.
        execute(update("money_accounts", id, "currency_code" to "XXX", "currency_decimals" to 0L))
        insert("money_movements", moneyMovementRow(id))
        // Con movimientos, ni la moneda ni sus decimales: los montos quedarían en otra.
        rejected(ACCOUNTS, update("money_accounts", id, "currency_code" to TEST_CURRENCY))
        rejected(ACCOUNTS, update("money_accounts", id, "currency_decimals" to 2L))
        execute(update("money_accounts", id, "balance" to 50_000L, "archived_at" to LATER))
        assertEquals(50_000L, count("SELECT balance FROM money_accounts WHERE id = $id"))
    }

    @Test
    fun eachMovementKeepsTheSignAndTheOriginOfItsType() = runBlocking {
        val accountId = insert("money_accounts", moneyAccountRow())
        val opening = moneyMovementRow(accountId)
        // Cada tipo con su signo y su columna de origen, si la tiene. Las sondas ponen un id
        // cualquiera: la guarda rechaza antes de que la llave lo mire.
        val origins =
            listOf(
                Triple("SALE_RECEIPT", 1L, "sale_payment_id"),
                Triple("CUSTOMER_PAYMENT", 1L, "customer_ledger_entry_id"),
                Triple("SUPPLIER_REFUND", 1L, "purchase_return_id"),
                Triple("OWNER_CONTRIBUTION", 1L, null),
                Triple("OTHER_INCOME", 1L, null),
                Triple("REFUND", -1L, "sale_return_id"),
                Triple("FEE", -1L, "sale_payment_id"),
                Triple("EXPENSE_PAYMENT", -1L, "expense_id"),
                Triple("PURCHASE_PAYMENT", -1L, "purchase_id"),
                Triple("SUPPLIER_PAYMENT", -1L, "supplier_ledger_entry_id"),
                Triple("OWNER_DRAW", -1L, null),
            )
        for ((type, sign, origin) in origins) {
            val valid = opening.with("type" to type, "amount" to sign * 1_000L)
            val complete = if (origin == null) valid else valid.with(origin to 1L)
            // El signo contrario se rechaza, y el origen es obligatorio.
            rejected(MOVEMENTS, complete.with("amount" to -sign * 1_000L).insertInto(TABLE))
            if (origin != null) rejected(MOVEMENTS, valid.insertInto(TABLE))
        }
        for (broken in listOf(
            opening.with("amount" to 0L),
            opening.with("type" to "CASH_DIFFERENCE"),
            opening.with("type" to "GIFT"),
            opening.with("amount" to 1_000.5),
            opening.with("balance_after" to "cincuenta mil"),
            opening.with("available_date" to "2026-04-31"),
            opening.with("fx_rate_micro" to 0L),
            opening.with("fx_rate_micro" to 1_000_000_000_000_001L),
            opening.with("note" to ""),
            opening.with("business_date" to "01/01/2026"),
            // Solo una transferencia lleva grupo, y siempre lo lleva.
            opening.with("transfer_group_id" to TRANSFER_GROUP),
            opening.with("type" to "TRANSFER_IN"),
            opening.with("type" to "TRANSFER_IN", "transfer_group_id" to "corto"),
        )) {
            rejected(MOVEMENTS, broken.insertInto(TABLE))
        }

        // El pago al proveedor apunta a su fila del libro del proveedor, que ya tiene tabla y llave.
        val supplierId = insert("suppliers", supplierRow())
        val supplierPayment =
            insert(
                "supplier_ledger_entries",
                supplierEntryRow(supplierId).with("type" to "PAYMENT", "amount" to -1L),
            )
        insert(TABLE, opening.with("amount" to -50_000L, "balance_after" to -50_000L))
        insert(TABLE, opening.with("type" to "OWNER_DRAW", "amount" to -10_000L))
        insert(TABLE, opening.with("type" to "OTHER_INCOME", "note" to "Reintegro"))
        val salePayment = database.insertSaleWithCardPayment(priceListId)
        insert(TABLE, opening.with("type" to "SALE_RECEIPT", "sale_payment_id" to salePayment))
        // Las devoluciones ya tienen llave: el movimiento válido apunta a unas que existen.
        val saleReturnId =
            database.saleReturnDao().insert(saleReturn(insert("sales", saleRow(priceListId))))
        val purchaseReturnId = database.purchaseReturnDao().insert(purchaseReturn())
        insert(
            TABLE,
            opening.with("type" to "SUPPLIER_REFUND", "purchase_return_id" to purchaseReturnId),
        )
        insert(
            TABLE,
            opening.with(
                "type" to "FEE",
                "amount" to -300L,
                "sale_payment_id" to salePayment,
                "available_date" to "2026-01-03",
            ),
        )
        insert(
            TABLE,
            opening.with("type" to "REFUND", "amount" to -1_000L, "sale_return_id" to saleReturnId),
        )
        insert(
            TABLE,
            opening.with(
                "type" to "SUPPLIER_PAYMENT",
                "amount" to -1L,
                "supplier_ledger_entry_id" to supplierPayment,
            ),
        )
        // Una diferencia de caja puede faltar o sobrar, y apunta a su sesión.
        val sessionId = insert("cash_sessions", cashSessionRow(accountId))
        val difference =
            opening.with("type" to "CASH_DIFFERENCE", "cash_session_id" to sessionId)
        insert(TABLE, difference.with("amount" to -500L))
        insert(TABLE, difference.with("amount" to 500L))
        insert(
            TABLE,
            opening.with(
                "type" to "TRANSFER_OUT",
                "amount" to -30_000L,
                "transfer_group_id" to TRANSFER_GROUP,
                "fx_rate_micro" to 4_150_000_000L,
            ),
        )
        insert(TABLE, opening.with("type" to "TRANSFER_IN", "transfer_group_id" to TRANSFER_GROUP))
        assertEquals(12L, count("SELECT COUNT(*) FROM money_movements"))
    }

    @Test
    fun aVoidPointsToAMovementOfTheSameAccountOnlyOnce() = runBlocking {
        val accountId = insert("money_accounts", moneyAccountRow())
        val otherId = insert("money_accounts", moneyAccountRow("billetera"))
        val opening = moneyMovementRow(accountId)
        val drawId = insert(TABLE, opening.with("type" to "OWNER_DRAW", "amount" to -10_000L))
        val otherDrawId =
            insert(
                TABLE,
                moneyMovementRow(otherId).with("type" to "OWNER_DRAW", "amount" to -10_000L),
            )
        val transferId =
            insert(
                TABLE,
                opening.with(
                    "type" to "TRANSFER_OUT",
                    "amount" to -30_000L,
                    "transfer_group_id" to TRANSFER_GROUP,
                ),
            )
        val undo =
            opening.with(
                "type" to "MOVEMENT_VOID",
                "amount" to 10_000L,
                "reverses_movement_id" to drawId,
            )
        for (broken in listOf(
            undo.with("amount" to 9_999L),
            undo.with("amount" to -10_000L),
            undo.with("reverses_movement_id" to otherDrawId),
            undo.with("reverses_movement_id" to MISSING_ID),
            undo.with("reverses_movement_id" to null),
            opening.with("reverses_movement_id" to drawId),
            // Una transferencia no se anula con un compensatorio: se anula con otra transferencia.
            undo.with("amount" to 30_000L, "reverses_movement_id" to transferId),
        )) {
            rejected(MOVEMENTS, broken.insertInto(TABLE))
        }

        val undoId = insert(TABLE, undo)
        // El mismo movimiento no se anula dos veces, y un compensatorio no se anula.
        rejected(MOVEMENTS, undo.insertInto(TABLE))
        rejected(
            MOVEMENTS,
            undo.with("amount" to -10_000L, "reverses_movement_id" to undoId).insertInto(TABLE),
        )
        assertEquals(4L, count("SELECT COUNT(*) FROM money_movements"))
    }

    @Test
    fun theMoneyLedgerOnlyGrows() = runBlocking {
        val accountId = insert("money_accounts", moneyAccountRow())
        val opening = moneyMovementRow(accountId)
        val id = insert(TABLE, opening)

        rejected(APPEND_ONLY, update(TABLE, id, "amount" to 60_000L))
        rejected(APPEND_ONLY, update(TABLE, id, "note" to "Corregido"))
        rejected(APPEND_ONLY, "DELETE FROM money_movements WHERE id = $id")
        rejected(MOVEMENTS, opening.with("amount" to 1_000L).withId(id).insertOrReplaceInto(TABLE))

        assertEquals(50_000L, count("SELECT amount FROM money_movements WHERE id = $id"))
        assertEquals(1L, count("SELECT COUNT(*) FROM money_movements"))
    }

    private companion object {
        const val MISSING_ID = 999L
        const val TABLE = "money_movements"
        const val ACCOUNTS = "guard:money_accounts"
        const val MOVEMENTS = "guard:money_movements"
        const val APPEND_ONLY = "guard:money_movements_append_only"
    }
}
