package io.github.yesidrobayo.shopledger.ui.product

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import kotlin.time.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La vista previa de la actualización masiva con 5000 productos inventados, en el teléfono: la meta
 * es un segundo o menos. Un emulador no es el teléfono de referencia: el tiempo se anota para
 * comparar. Solo registra conteos y duraciones, nunca un nombre ni un monto.
 */
@RunWith(AndroidJUnit4::class)
class BulkPricePreviewTimingTest {
    private val catalog = (1..PRODUCTS).map(::sampleProduct).sortedBy { it.details.normalizedName }

    @Test
    fun previewOfFiveThousandProductsTakesLessThanASecond() {
        val request = BulkPriceRequest(BulkPriceScope.All, BasisPoints(800L), Money(100L))
        // La primera vuelta calienta el código: no cuenta.
        BulkPricePreviewer.preview(catalog, request)
        val times = List(ROUNDS) {
            val mark = TimeSource.Monotonic.markNow()
            val preview = BulkPricePreviewer.preview(catalog, request)
            val elapsed = mark.elapsedNow().inWholeMilliseconds
            assertEquals(PRODUCTS, preview.changes.size + preview.unchanged + preview.excluded.size)
            elapsed
        }.sorted()
        val median = times[ROUNDS / 2]
        Log.i(
            TAG,
            "vista previa de $PRODUCTS productos: mediana $median ms, máximo ${times.last()} ms",
        )
        assertTrue("La vista previa tardó $median ms", median <= LIMIT_MILLIS)
    }

    private companion object {
        const val PRODUCTS = 5_000
        const val ROUNDS = 7
        const val LIMIT_MILLIS = 1_000L
        const val TAG = "BulkPriceTiming"
    }
}
