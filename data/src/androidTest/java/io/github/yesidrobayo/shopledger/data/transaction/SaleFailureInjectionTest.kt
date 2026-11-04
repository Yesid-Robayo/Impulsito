package io.github.yesidrobayo.shopledger.data.transaction

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una venta de tres líneas sobre Room real, con una falla inyectada después de cada paso: leer los
 * productos, la cabecera y, por cada línea, la línea, su fila del libro y el saldo del producto.
 * Después también al contar el cambio, al confirmar y con un rechazo al final.
 *
 * En cada punto se prueban tres fallas: una excepción del código, un error real de SQLite por una
 * llave rota y un error de lectura o escritura como el que lanzaría un DAO. Tras cada una, la base
 * tiene las mismas filas de ventas y del libro, y los productos están igual, columna por columna.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleFailureInjectionTest : SaleTransactionTest() {
    @Test
    fun failureAfterAnyStepLeavesNothing() = runBlocking {
        val before = database.snapshot()
        val points = StepPoint.all(threeLines.size)

        for (point in points) {
            for (kind in Injected.entries) {
                val case = "$kind después de $point"
                val outcome = outcomeOf { sales(threeLines) { if (it == point) inject(kind) } }

                assertEquals(case, kind.expected, outcome)
                assertEquals(case, before, database.snapshot())
                database.assertNothingHalfWritten(case)
            }
        }
        assertEquals(0, changes.calls)
        assertEquals(0, listener.calls)
        // Once puntos con tres fallas cada uno: ningún punto quedó sin probar.
        assertEquals(11, points.size)
    }

    @Test
    fun failureWhileCountingTheChangeLeavesNothing() = runBlocking {
        val before = database.snapshot()
        changes.failure = InjectedFailure()

        val outcome = outcomeOf { sales(threeLines) }

        assertEquals("InjectedFailure", outcome)
        assertEquals(before, database.snapshot())
        database.assertNothingHalfWritten("la falla al contar el cambio")
        assertEquals(0, listener.calls)
    }

    @Test
    fun failureAtCommitLeavesNothing() = runBlocking {
        val before = database.snapshot()
        val lastPoint = StepPoint.all(threeLines.size).last()

        val outcome =
            outcomeOf {
                sales(threeLines) { point ->
                    if (point == lastPoint) {
                        // La llave rota se revisa al confirmar, con la venta entera ya escrita.
                        database.useWriterConnection {
                            it.executeSQL("PRAGMA defer_foreign_keys = ON")
                        }
                        database.saleLineDao().insert(
                            saleLine(MISSING_ID, stock.first, priceListId),
                        )
                    }
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION.name, outcome)
        assertEquals(before, database.snapshot())
        database.assertNothingHalfWritten("la falla al confirmar")
        assertEquals(0, listener.calls)
    }

    @Test
    fun rejectionAfterEveryWriteLeavesNothing() = runBlocking {
        val before = database.snapshot()

        val saleId = sales(threeLines, reject = true)

        assertEquals(null, saleId)
        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
    }

    @Test
    fun theSameSaleWithoutFailuresIsComplete() = runBlocking {
        val before = database.snapshot()

        sales(threeLines)

        val after = database.snapshot()
        assertEquals(before.sales + 1, after.sales)
        assertEquals(before.saleLines + 3, after.saleLines)
        assertEquals(before.movements + 3, after.movements)
        // Dos líneas del primer producto y una del segundo, de dos unidades cada una.
        val onHand = after.products.associate { it.id to it.onHandMilli }
        assertEquals(10_000L - 2 * SOLD_MILLI, onHand[stock.first])
        assertEquals(10_000L - SOLD_MILLI, onHand[stock.second])
        database.assertNothingHalfWritten("la venta completa")
        assertEquals(1, changes.calls)
    }

    private suspend fun inject(kind: Injected) {
        when (kind) {
            Injected.CODE -> throw InjectedFailure()

            // Un error real de SQLite dentro de la transacción: una línea de una venta que no existe.
            Injected.CONSTRAINT ->
                database.saleLineDao().insert(saleLine(MISSING_ID, stock.first, priceListId))

            Injected.IO -> throw sqliteError(SQLITE_IOERR_WRITE, "disk I/O error")
        }
    }

    /** Cómo terminó la venta: el nombre de la falla de almacenamiento o de la excepción. */
    private suspend fun outcomeOf(block: suspend () -> Unit): String {
        try {
            block()
        } catch (failure: StorageFailureException) {
            return failure.failure.name
        } catch (failure: InjectedFailure) {
            return failure.javaClass.simpleName
        }
        fail("La venta debía fallar")
        error("Inalcanzable")
    }

    private enum class Injected(val expected: String) {
        CODE("InjectedFailure"),
        CONSTRAINT(StorageFailure.INVARIANT_VIOLATION.name),
        IO(StorageFailure.IO_ERROR.name),
    }

    private companion object {
        const val MISSING_ID = 999_999L
        const val SQLITE_IOERR_WRITE = 778
    }
}
