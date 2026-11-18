package io.github.yesidrobayo.shopledger.update

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El trabajo diario de la búsqueda de versión en el teléfono, con una búsqueda falsa: nunca habla
 * con el aviso real. Termina siempre bien, también sin red, con una firma que no coincide o con
 * una falla, para que el trabajo periódico siga y lo intente al día siguiente. Avisa una sola vez
 * por versión. Versiones inventadas.
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "ab".repeat(32)
    private val newVersion =
        UpdateCheckOutcome.NewVersion(
            versionName = "1.0.8",
            publishedAt = Instant.parse("2026-11-18T00:00:00Z"),
            apkSha256 = hash,
            important = false,
            versionCode = 8,
        )

    private var lastNotified: Long? = null
    private val shown = mutableListOf<KnownUpdate>()

    private fun run(
        outcome: () -> UpdateCheckOutcome,
        enabled: Boolean = true,
    ): ListenableWorker.Result {
        val check =
            AutomaticUpdateCheck(
                enabled = { enabled },
                check = { outcome() },
                lastNotified = { lastNotified },
                notify = { update ->
                    shown += update
                    true
                },
                markNotified = { code ->
                    lastNotified = code
                    true
                },
                log = NoLog,
            )
        val worker =
            TestListenableWorkerBuilder<UpdateCheckWorker>(context)
                .setWorkerFactory(Factory(check))
                .build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun aNewVersionIsNotifiedOnceAndTheWorkSucceeds() {
        assertEquals(ListenableWorker.Result.success(), run({ newVersion }))
        assertEquals(ListenableWorker.Result.success(), run({ newVersion }))

        assertEquals(listOf(KnownUpdate(8, "1.0.8")), shown)
        assertEquals(8L, lastNotified)
    }

    @Test
    fun withoutNetworkOrWithABadSignatureItSucceedsQuietly() {
        assertEquals(ListenableWorker.Result.success(), run({ UpdateCheckOutcome.Offline }))
        assertEquals(ListenableWorker.Result.success(), run({ UpdateCheckOutcome.BadSignature }))
        assertEquals(ListenableWorker.Result.success(), run({ UpdateCheckOutcome.Unreadable }))

        assertTrue(shown.isEmpty())
    }

    @Test
    fun aFailureNeverLeavesTheWorker() {
        val result = run({ throw IOException("sin red") })

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun turnedOffItNotifiesNothing() {
        assertEquals(ListenableWorker.Result.success(), run({ newVersion }, enabled = false))

        assertTrue(shown.isEmpty())
    }

    private class Factory(private val check: AutomaticUpdateCheck) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = UpdateCheckWorker(appContext, workerParameters, check)
    }

    private object NoLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }
}
