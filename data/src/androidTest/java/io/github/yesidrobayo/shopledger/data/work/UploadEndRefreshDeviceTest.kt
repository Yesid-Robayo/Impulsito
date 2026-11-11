package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupStatusReader
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentFile
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentGate
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupStatusUseCase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Con la pantalla abierta, el fin de una subida cambia el estado sin otro cambio y sin volver a
 * abrir: antes, la última escritura de `backup_state` llegaba con el trabajo todavía corriendo, y la
 * pantalla seguía en `UPLOADING`. Aquí el trabajo de subida es uno de prueba que espera una señal,
 * y no toca la base.
 */
@RunWith(AndroidJUnit4::class)
class UploadEndRefreshDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "upload-end-refresh-test")
    private val outside = File(context.noBackupFilesDir, "upload-end-refresh-outside")
    private lateinit var manager: WorkManager

    @Before
    fun open() = runBlocking {
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(Executors.newSingleThreadExecutor())
                .setWorkerFactory(HeldFactory)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        manager = WorkManager.getInstance(context)
        HeldFactory.release = CountDownLatch(1)
        outside.deleteRecursively()
        fixture.open()
        fixture.connect()
        fixture.addProduct("Pan de prueba")
    }

    @After
    fun close() {
        HeldFactory.release.countDown()
        manager.cancelAllWork().result.get()
        manager.pruneWork().result.get()
        fixture.close()
        outside.deleteRecursively()
    }

    @Test
    fun theEndOfTheUploadLeavesUploadingWithoutAnyOtherChange() = runBlocking {
        // El entorno de la app, el mismo que arma la capa de datos, con la política aceptada.
        val consent =
            PolicyConsentGate(
                PolicyConsentFile({ outside }, Dispatchers.IO),
                PolicyVersions.CURRENT,
                fixture.log,
                onAccepted = {},
            )
        consent.acceptCurrent()
        val environment =
            productionBackupStatusEnvironment(context, outside, consent, Dispatchers.IO)
        val status =
            ObserveBackupStatusUseCase(
                BackupStatusReader(
                    state = fixture.ports.backupState,
                    meteredPolicy = { MeteredBackupPolicy.EVERY_60_MIN },
                    environment = environment,
                    ioContext = Dispatchers.IO,
                ),
                fixture.clock,
            )
        val seen = MutableStateFlow<BackupStatusReport?>(null)
        val watching = launch(Dispatchers.IO) { status().collect { seen.value = it } }
        try {
            // Antes de la subida, cualquier estado menos subiendo: la red del emulador decide cuál.
            withTimeout(WAIT_MS) { seen.first { it.isNotUploading() } }

            manager
                .enqueueUniqueWork(
                    BackupWorkNames.UPLOAD,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<HeldUpload>().build(),
                ).result
                .get()
            withTimeout(WAIT_MS) { seen.first { it?.status == BackupStatus.UPLOADING } }

            // Termina la subida, sin escribir nada en la base: el estado sale de UPLOADING solo.
            HeldFactory.release.countDown()
            val after = withTimeout(WAIT_MS) { seen.first { it.isNotUploading() } }

            assertNotEquals(BackupStatus.UPLOADING, after?.status)
        } finally {
            watching.cancel()
        }
    }

    private fun BackupStatusReport?.isNotUploading(): Boolean =
        this != null && status != BackupStatus.UPLOADING

    /** Una subida de prueba que corre hasta que la prueba la suelta. */
    class HeldUpload(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result {
            HeldFactory.release.await(WAIT_MS, TimeUnit.MILLISECONDS)
            return Result.success()
        }
    }

    private object HeldFactory : WorkerFactory() {
        @Volatile
        var release = CountDownLatch(1)

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = HeldUpload(appContext, workerParameters)
    }

    private companion object {
        const val WAIT_MS = 20_000L
    }
}
