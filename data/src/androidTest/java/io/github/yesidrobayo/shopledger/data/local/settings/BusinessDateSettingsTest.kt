package io.github.yesidrobayo.shopledger.data.local.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseEntity
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La fecha de negocio y la semana con la zona y el primer día que guarda `app_settings`: cada fila
 * se fecha con la zona vigente al escribirla, y cambiar la zona o la semana no reescribe ninguna
 * fila, tampoco al reabrir la base. Una base con una zona que este teléfono no conoce abre igual,
 * y el calendario lo dice en vez de usar otra.
 *
 * Los gastos sirven de registro con fecha: tienen instante y fecha de negocio como cualquier otro.
 */
@RunWith(AndroidJUnit4::class)
internal class BusinessDateSettingsTest : CatalogDatabaseTest() {
    private val settings get() = database.appSettingsDao()
    private val expenses get() = database.expenseDao()
    private var categoryId = 0L

    @Before
    fun createCategory() = runBlocking {
        categoryId = database.expenseCategoryDao().insert(expenseCategory("Servicios"))
    }

    @Test
    fun changingTheZoneKeepsTheBusinessDateOfEveryRowAlreadyWritten() = runBlocking {
        assertEquals(1, settings.updateBusinessTimeZone("Etc/GMT+5", LATER))
        val written = stamp(SAME_INSTANT)
        assertEquals(date("2026-11-01"), written.businessDate)
        val oldId = expenses.insert(expenseAt(written))
        val oldRow = expenses.findById(oldId)

        // La persona cambia la zona: solo esa columna y el último cambio.
        val beforeChange = requireNotNull(settings.get())
        assertEquals(1, settings.updateBusinessTimeZone("Etc/GMT-9", MUCH_LATER))
        assertEquals(
            beforeChange.copy(businessTimeZone = "Etc/GMT-9", updatedAt = MUCH_LATER),
            settings.get(),
        )

        // La fila escrita conserva su fecha, también en el disco, al reabrir la base.
        assertEquals(oldRow, expenses.findById(oldId))
        reopen()
        assertEquals(oldRow, expenses.findById(oldId))

        // Un registro nuevo en el mismo instante cae en el día de la zona nueva.
        val writtenAfter = stamp(SAME_INSTANT)
        assertEquals(date("2026-11-02"), writtenAfter.businessDate)
        val newId = expenses.insert(expenseAt(writtenAfter))
        assertEquals("2026-11-02", expenses.findById(newId)?.businessDate)
        assertEquals("2026-11-01", expenses.findById(oldId)?.businessDate)
    }

    @Test
    fun changingTheFirstDayOfTheWeekRegroupsWithoutTouchingAnyRow() = runBlocking {
        // Con la zona de la base de prueba, tres horas por detrás de UTC: lunes 2026-11-02.
        val written = stamp(SAME_INSTANT)
        assertEquals(date("2026-11-02"), written.businessDate)
        val id = expenses.insert(expenseAt(written))
        val row = expenses.findById(id)

        val weekStarts = listOf(1L to "2026-11-02", 6L to "2026-10-31", 7L to "2026-11-01")
        for ((firstDay, start) in weekStarts) {
            val before = requireNotNull(settings.get())
            assertEquals(1, settings.updateWeekStartDay(firstDay, LATER))
            // Solo esa columna y el último cambio.
            assertEquals(before.copy(weekStartDay = firstDay, updatedAt = LATER), settings.get())

            assertEquals(date(start), calendar().week(written.businessDate).start)
            assertEquals(row, expenses.findById(id))
        }
    }

    @Test
    fun aDatabaseWithAZoneThisPhoneDoesNotKnowOpensAndReportsItWithoutReplacingIt() = runBlocking {
        val written = stamp(SAME_INSTANT)
        val id = expenses.insert(expenseAt(written))
        val row = expenses.findById(id)
        // Como si llegara en una copia de un teléfono con zonas más nuevas: la guarda solo revisa
        // su forma.
        assertEquals(1, settings.updateBusinessTimeZone(UNKNOWN_ZONE, LATER))

        reopen()

        val stored = requireNotNull(settings.get())
        assertEquals(UNKNOWN_ZONE, stored.businessTimeZone)
        assertEquals(
            BusinessCalendarResult.UnknownZone(UNKNOWN_ZONE),
            BusinessCalendar.of(stored.businessTimeZone, DayOfWeek(stored.weekStartDay.toInt())),
        )
        assertEquals(row, expenses.findById(id))
    }

    @Test
    fun theGuardRejectsAZoneOrAFirstDayOutOfFormThroughTheDao() = runBlocking {
        val before = settings.get()

        guardFailure(SETTINGS_GUARD) { settings.updateBusinessTimeZone("", LATER) }
        guardFailure(SETTINGS_GUARD) { settings.updateBusinessTimeZone("Z".repeat(65), LATER) }
        guardFailure(SETTINGS_GUARD) { settings.updateWeekStartDay(0, LATER) }
        guardFailure(SETTINGS_GUARD) { settings.updateWeekStartDay(8, LATER) }

        assertEquals(before, settings.get())
    }

    /** El calendario de los ajustes guardados ahora; en estas pruebas la zona siempre existe. */
    private suspend fun calendar(): BusinessCalendar {
        val row = requireNotNull(settings.get())
        return when (
            val result =
                BusinessCalendar.of(row.businessTimeZone, DayOfWeek(row.weekStartDay.toInt()))
        ) {
            is BusinessCalendarResult.Ready -> result.calendar
            is BusinessCalendarResult.UnknownZone -> error("Zona desconocida en la prueba")
        }
    }

    private suspend fun stamp(epochMillis: Long): RecordTime =
        calendar().stamp(Instant.fromEpochMilliseconds(epochMillis))

    private fun expenseAt(time: RecordTime): ExpenseEntity = expense(
        categoryId,
        businessDate = time.businessDate.toString(),
        occurredAt = time.occurredAt.toEpochMilliseconds(),
    )

    /** Cierra la base y la abre otra vez desde el disco. Ya existe: no nace ninguna fila. */
    private fun reopen() {
        database.close()
        database =
            runBlocking {
                AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK) {
                    error("La base ya existe: no se debe crear otra")
                }
            }
    }

    private companion object {
        /** 2026-11-02 04:30 UTC: un día distinto según la zona del negocio. */
        const val SAME_INSTANT = 1_793_593_800_000L

        /** Un identificador con forma de zona que ningún teléfono conoce. */
        const val UNKNOWN_ZONE = "Mars/Olympus_Mons"

        const val SETTINGS_GUARD = "guard:app_settings"

        fun date(text: String): BusinessDate = requireNotNull(BusinessDate.parse(text))
    }
}
