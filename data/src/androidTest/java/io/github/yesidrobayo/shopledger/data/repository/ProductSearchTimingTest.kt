package io.github.yesidrobayo.shopledger.data.repository

import android.util.Log
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Medición de referencia de la búsqueda y la lista de productos con 5.000 productos, el volumen que
 * pide el requisito de la búsqueda. Corre en un emulador, que no es el teléfono de referencia: los
 * tiempos solo sirven para comparar, y por eso se anotan en el registro sin exigir un tope. Lo que sí
 * se exige es que cada consulta devuelva lo que debe.
 *
 * Solo registra conteos y duraciones: ningún nombre de producto.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductSearchTimingTest : RepositoryTest() {
    @Test
    fun searchAndListWithFiveThousandProducts() = runBlocking {
        seedProducts()
        val products = RoomProductRepository(database)

        // La primera vuelta calienta la caché de páginas y el código: no cuenta.
        products.observe(ArchiveFilter.ACTIVE).first()
        val listTimes =
            List(ROUNDS) {
                val mark = TimeSource.Monotonic.markNow()
                val list = products.observe(ArchiveFilter.ACTIVE).first()
                val elapsed = mark.elapsedNow().inWholeMicroseconds
                assertEquals(PRODUCTS + 1, list.size)
                elapsed
            }
        Log.i(
            TAG,
            "lista de vigentes, ${PRODUCTS + 1} productos: mediana ${median(
                listTimes,
            )} us, máximo ${listTimes.max()} us",
        )

        // Cada carácter más que escribe la persona es una consulta nueva.
        for (term in listOf("a", "ar", "arr", "arro", "arroz", "arroz 4")) {
            search(term)
            val times =
                List(ROUNDS) {
                    val mark = TimeSource.Monotonic.markNow()
                    val found = search(term)
                    val elapsed = mark.elapsedNow().inWholeMicroseconds
                    assertTrue(found.size <= SEARCH_LIMIT)
                    assertTrue(found.isNotEmpty())
                    assertTrue(found.all { it.contains(term) })
                    elapsed
                }
            Log.i(
                TAG,
                "búsqueda de ${term.length} caracteres: mediana ${median(
                    times,
                )} us, máximo ${times.max()} us",
            )
        }
    }

    /** La búsqueda por texto contenido del modelo de datos, con el término ya normalizado. */
    private suspend fun search(term: String): List<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared(SEARCH_SQL) { statement ->
                statement.bindText(1, term)
                buildList { while (statement.step()) add(statement.getText(1)) }
            }
        }

    /** 5.000 productos inventados, en una sola transacción: diez palabras por mil números. */
    private suspend fun seedProducts() {
        database.withWriteTransaction {
            val dao = database.productDao()
            for (index in 0 until PRODUCTS) {
                val name = "${WORDS[index % WORDS.size]} ${index / WORDS.size}"
                dao.insert(product(name))
            }
        }
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private companion object {
        const val TAG = "ProductSearchTiming"
        const val PRODUCTS = 5_000
        const val ROUNDS = 15
        const val SEARCH_LIMIT = 50
        val WORDS =
            listOf(
                "arroz",
                "frijol",
                "lenteja",
                "azucar",
                "harina",
                "aceite",
                "jabon",
                "galleta",
                "panela",
                "avena",
            )
        const val SEARCH_SQL =
            "SELECT id, normalized_name, base_unit_id, sale_price, average_cost, on_hand_milli, " +
                "min_stock_milli FROM products WHERE archived_at IS NULL " +
                "AND normalized_name LIKE '%' || ? || '%' ESCAPE '\\' " +
                "ORDER BY normalized_name LIMIT 50"
    }
}
