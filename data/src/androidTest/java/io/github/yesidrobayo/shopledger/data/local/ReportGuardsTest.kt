package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de los reportes: metas e índice de precios, de un mes `YYYY-MM`, y
 * gastos recurrentes, cuyo día cabe en su período. Cada guarda rechaza lo que debe, con su
 * mensaje, y deja pasar lo válido, al insertar y al actualizar.
 */
@RunWith(AndroidJUnit4::class)
internal class ReportGuardsTest : GuardProbeTest() {
    @Test
    fun goalsAreForAMonthWrittenAsYearAndMonth() = runBlocking {
        val valid = goalRow("2026-03")
        for (broken in BAD_MONTHS.map { valid.with("month" to it) } + listOf(
            valid.with("sales_goal" to -1L),
            valid.with("sales_goal" to 9_000_000_000_000_001L),
            valid.with("sales_goal" to 10.5),
            valid.with("profit_goal" to -9_000_000_000_000_001L),
            valid.with("profit_goal" to "mucho"),
            valid.with("updated_at" to null),
        )) {
            rejected(GOALS, broken.insertInto("business_goals"))
        }

        val id = insert("business_goals", valid)
        insert(
            "business_goals",
            goalRow("2026-12").with(
                "sales_goal" to 9_000_000_000_000_000L,
                "profit_goal" to -9_000_000_000_000_000L,
            ),
        )
        insert("business_goals", goalRow("2027-01").with("sales_goal" to null))
        for (month in BAD_MONTHS) rejected(GOALS, update("business_goals", id, "month" to month))
        rejected(GOALS, update("business_goals", id, "sales_goal" to -1L))
        execute(update("business_goals", id, "month" to "2026-02", "updated_at" to LATER))
        assertEquals(1L, count("SELECT COUNT(*) FROM business_goals WHERE month = '2026-02'"))
    }

    @Test
    fun theIndexIsForAMonthAndWithinItsRange() = runBlocking {
        val valid = indexRow("2026-03")
        for (broken in BAD_MONTHS.map { valid.with("month" to it) } + listOf(
            valid.with("change_bp" to -10_001L),
            valid.with("change_bp" to 1_000_001L),
            valid.with("change_bp" to 1.5),
            valid.with("change_bp" to null),
            valid.with("source_note" to ""),
            valid.with("source_note" to "N".repeat(121)),
            valid.with("created_at" to "ayer"),
        )) {
            rejected(INDEX, broken.insertInto("price_index_entries"))
        }

        val id = insert("price_index_entries", valid)
        insert("price_index_entries", indexRow("2026-04").with("change_bp" to -10_000L))
        insert(
            "price_index_entries",
            indexRow("2026-05").with("change_bp" to 1_000_000L, "source_note" to "N".repeat(120)),
        )
        for (month in BAD_MONTHS) {
            rejected(INDEX, update("price_index_entries", id, "month" to month))
        }
        rejected(INDEX, update("price_index_entries", id, "change_bp" to 1_000_001L))
        execute(update("price_index_entries", id, "change_bp" to 0L, "source_note" to null))
        assertEquals(0L, count("SELECT change_bp FROM price_index_entries WHERE id = $id"))
    }

    @Test
    fun aRecurringExpenseFallsOnADayOfItsPeriod() = runBlocking {
        val categoryId = insert("expense_categories", expenseCategoryRow())
        val valid = recurringRow(categoryId)
        for (broken in listOf(
            valid.with("recurrence" to "DAILY"),
            valid.with("recurrence" to null),
            valid.with("recurrence" to "WEEKLY", "day_of_period" to 8L),
            valid.with("day_of_period" to 32L),
            valid.with("recurrence" to "YEARLY", "day_of_period" to 367L),
            valid.with("day_of_period" to 0L),
            valid.with("day_of_period" to 5.5),
            valid.with("amount" to 0L),
            valid.with("amount" to 1_000_000_000_000_001L),
            valid.with("description" to ""),
            valid.with("description" to "D".repeat(201)),
            valid.with("next_due_date" to "2026-02-30"),
            valid.with("next_due_date" to "2026-2-5"),
            valid.with("reminder_enabled" to 2L),
            valid.with("archived_at" to "hoy"),
        )) {
            rejected(RECURRING, broken.insertInto("recurring_expenses"))
        }

        val id = insert("recurring_expenses", valid)
        insert("recurring_expenses", valid.with("recurrence" to "WEEKLY", "day_of_period" to 7L))
        insert("recurring_expenses", valid.with("recurrence" to "YEARLY", "day_of_period" to 366L))
        insert(
            "recurring_expenses",
            valid.with("amount" to 1_000_000_000_000_000L, "description" to "D".repeat(200)),
        )
        rejected(RECURRING, update("recurring_expenses", id, "day_of_period" to 32L))
        rejected(
            RECURRING,
            update("recurring_expenses", id, "recurrence" to "WEEKLY", "day_of_period" to 31L),
        )
        rejected(RECURRING, update("recurring_expenses", id, "next_due_date" to "mañana"))
        execute(update("recurring_expenses", id, "archived_at" to LATER))
        assertEquals(1L, count("SELECT COUNT(*) FROM recurring_expenses WHERE id = $id"))
    }

    private fun goalRow(month: String): Row = row(
        "month" to month,
        "sales_goal" to 5_000_000L,
        "profit_goal" to 1_000_000L,
        "updated_at" to CREATED_AT,
    )

    private fun indexRow(month: String): Row = row(
        "month" to month,
        "change_bp" to 120L,
        "source_note" to "Boletín mensual",
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    /** Un arriendo mensual del día 31, el último que admite un mes. */
    private fun recurringRow(categoryId: Long): Row = row(
        "expense_category_id" to categoryId,
        "amount" to 900_000L,
        "description" to "Arriendo del local",
        "recurrence" to "MONTHLY",
        "day_of_period" to 31L,
        "next_due_date" to "2026-01-31",
        "money_account_id" to null,
        "reminder_enabled" to 1L,
        "archived_at" to null,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private companion object {
        const val GOALS = "guard:business_goals"
        const val INDEX = "guard:price_index_entries"
        const val RECURRING = "guard:recurring_expenses"

        /** Meses mal escritos: sin cero, con día, de otro siglo abreviado, imposibles o nulos. */
        val BAD_MONTHS: List<Any?> =
            listOf(
                "2026-3",
                "2026-03-01",
                "26-03",
                "2026-13",
                "2026-00",
                "2026/03",
                "",
                202603L,
                null,
            )
    }
}
