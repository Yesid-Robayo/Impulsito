package io.github.yesidrobayo.shopledger.data.work

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.BackupScheduler
import io.github.yesidrobayo.shopledger.data.backup.RetryReason
import io.github.yesidrobayo.shopledger.data.backup.ScheduleFacts
import io.github.yesidrobayo.shopledger.data.backup.TestControl
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.HttpClientFactory
import io.github.yesidrobayo.shopledger.data.destination.TEST_PASSWORD
import io.github.yesidrobayo.shopledger.data.destination.TEST_USER
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.WebDavClient
import io.github.yesidrobayo.shopledger.data.destination.WebDavCredential
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.WebDavEndpoint
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La copia remota en modo avión de verdad, con la radio apagada y ninguna red activa: lo único
 * del alcance que espera a la red. Con un servicio WebDAV conectado, los cambios del negocio se
 * guardan en el teléfono igual, `Respaldar ahora` deja la subida encolada hasta que haya red, y
 * una subida que el sistema corre sin red no pierde nada: se reintenta, con la copia pendiente y
 * la base entera.
 *
 * El servicio es el cliente real de la app hacia una dirección inventada: sin red, la petición no
 * sale del teléfono. Ningún dato llega a un servicio real.
 */
@RunWith(AndroidJUnit4::class)
class RemoteCopyWaitsOfflineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "remote-copy-offline-test")
    private val airplane = AirplaneMode(context)
    private lateinit var manager: WorkManager
    private lateinit var scheduler: BackupScheduler

    @Before
    fun prepare() = runBlocking {
        fixture.open()
        fixture.connect()
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        manager = WorkManager.getInstance(context)
        scheduler =
            BackupScheduler(
                facts = {
                    ScheduleFacts.of(
                        fixture.ports.backupState.get(),
                        fixture.ports.settings.get().preferences.meteredBackupPolicy,
                    )
                },
                restorePending = { false },
                policyAccepted = { true },
                queue = WorkManagerBackupQueue(context),
                clock = fixture.clock,
            )
        airplane.turnOn()
    }

    @After
    fun clean() {
        airplane.restore()
        if (::manager.isInitialized) {
            manager.cancelAllWork().result.get()
            manager.pruneWork().result.get()
        }
        fixture.close()
    }

    @Test
    fun withoutNetworkTheRemoteCopyStaysPendingAndNothingIsLost() = runBlocking {
        assertTrue(airplane.offline)
        val before = fixture.state()

        // Tres cambios del negocio, sin red: se guardan en el teléfono como siempre.
        val names = listOf("Pan de prueba", "Leche de prueba", "Queso de prueba")
        for (name in names) fixture.addProduct(name)
        val changed = fixture.state()
        assertTrue(changed.changeSeq > before.changeSeq)
        assertEquals(before.verifiedSeq, changed.verifiedSeq)

        // `Respaldar ahora` responde enseguida y deja la subida esperando una red, sin correrla.
        assertEquals(BackupNowRequest.QUEUED, scheduler.backupNow())
        val queued = pending(BackupWorkNames.UPLOAD)
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)

        // Si el sistema la corre igual, sin red: el cliente real no llega al servicio, y la
        // ejecución se reintenta sin tocar nada del teléfono. Cada intento cuenta como falla de
        // red.
        val destination =
            WebDavDestination(
                client =
                    WebDavClient(
                        endpoint = WebDavEndpoint(OFFLINE_SERVICE.toHttpUrl()),
                        credential = WebDavCredential(TEST_USER, TEST_PASSWORD),
                        http = HttpClientFactory.newClient(),
                    ),
                folder = APP_FOLDER,
                sleep = {},
            )
        for (attempt in 1L..RETRIES) {
            fixture.advance(5.minutes)
            val control = TestControl(metered = isMetered(context), backupNow = true)

            val run = fixture.runner(destination).run(control)

            assertEquals(
                BackupRun.Retry(RetryReason.TRANSIENT, transientKind = TransientKind.NETWORK),
                run,
            )
            val after = fixture.state()
            assertEquals(changed.changeSeq, after.changeSeq)
            assertEquals(changed.verifiedSeq, after.verifiedSeq)
            assertEquals(changed.lastUploadFinishedAt, after.lastUploadFinishedAt)
            assertEquals(attempt, after.consecutiveFailures)
            assertEquals("NETWORK", after.lastFailureKind)
            // Unos pocos intentos sin red son una espera, no un fallo a la vista.
            assertNull(after.failureCode)
        }

        // La copia sigue pendiente, la base tiene cada cambio y la subida sigue esperando la red.
        val last = fixture.state()
        assertTrue(last.changeSeq > last.verifiedSeq)
        assertEquals(
            names.sorted(),
            fixture.ports.products
                .observe(ArchiveFilter.ACTIVE)
                .first()
                .map { it.details.name }
                .sorted(),
        )
        assertEquals(WorkInfo.State.ENQUEUED, pending(BackupWorkNames.UPLOAD).state)
        assertTrue(airplane.offline)
    }

    /** El trabajo [name] que espera para correr. */
    private suspend fun pending(name: String): WorkInfo = withTimeout(WAIT) {
        manager
            .getWorkInfosForUniqueWorkFlow(name)
            .first { infos -> infos.any { !it.state.isFinished } }
            .single { !it.state.isFinished }
    }

    private companion object {
        val WAIT = 60.seconds

        /** Menos que los intentos seguidos que dejan el fallo a la vista. */
        const val RETRIES = 3L

        /** Un servicio inventado, de un dominio reservado para ejemplos: nunca recibe nada. */
        const val OFFLINE_SERVICE = "https://dav.example.com/dav/"
    }
}
