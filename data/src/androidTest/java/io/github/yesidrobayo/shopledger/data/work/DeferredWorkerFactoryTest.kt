package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La fábrica diferida arma la verdadera solo al pedirse el primer trabajo, una sola vez, y entrega
 * lo que esa crea. Así entregarla a la biblioteca de trabajos al arrancar no arma nada pesado.
 */
@RunWith(AndroidJUnit4::class)
class DeferredWorkerFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun buildsTheRealFactoryOnlyWhenTheFirstWorkerIsCreated() {
        var built = 0
        val real = RecordingFactory()
        val factory = DeferredWorkerFactory {
            built++
            real
        }
        assertEquals(0, built)

        val first = worker(factory)
        val second = worker(factory)

        assertEquals(1, built)
        assertEquals(2, real.created.size)
        assertSame(real.created[0], first)
        assertSame(real.created[1], second)
    }

    private fun worker(factory: WorkerFactory): ListenableWorker =
        TestListenableWorkerBuilder<IdleWorker>(context).setWorkerFactory(factory).build()

    /** Crea un trabajo que no hace nada y guarda cada uno que creó. */
    private class RecordingFactory : WorkerFactory() {
        val created = mutableListOf<ListenableWorker>()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = IdleWorker(appContext, workerParameters).also { created += it }
    }

    /** Un trabajo que termina sin hacer nada. */
    private class IdleWorker(context: Context, parameters: WorkerParameters) :
        CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result = Result.success()
    }
}
