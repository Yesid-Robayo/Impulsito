package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.PinRelease
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los ajustes del respaldo sobre el destino falso: liberar una copia fijada solo a pedido, sin
 * borrarla y nunca con una copia en curso; el consumo del mes, que empieza de cero en otro mes; y
 * volver a programar la subida con otra política. Datos inventados.
 */
class BackupSettingsServiceTest {
    private val destination = FakeBackupDestination()
    private val dir: File = Files.createTempDirectory("upload-usage").toFile()
    private val usageFile = File(dir, UploadUsageStore.FILE_NAME)
    private val lock = Mutex()
    private var row: BackupStateEntity? = row()
    private var month: String? = "2026-11"
    private var opened: DestinationOpen = DestinationOpen.Opened(destination)
    private var rescheduled = 0

    private val service =
        BackupSettingsService(
            state = { row },
            destinations = { opened },
            usage = UploadUsageStore({ usageFile }, Dispatchers.Unconfined),
            month = { month },
            backupLock = lock,
            rescheduled = { rescheduled += 1 },
        )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun releasingAPinnedCopyRemovesOnlyTheMarkAndKeepsTheCopy() = runBlocking {
        val ref = put(pinned = true)

        assertEquals(PinRelease.RELEASED, service.releasePinnedCopy(ref.value))

        assertEquals(false, metaOf(ref)?.pinned)
        assertTrue(destination.file(ref.value) != null)
        assertEquals(0, destination.calls.count { it == Operation.DELETE })
    }

    @Test
    fun aFailedReleaseLeavesTheCopyPinned() = runBlocking {
        val ref = put(pinned = true)
        destination.failNext(
            Operation.REPLACE_META,
            DestinationFailure.Transient(TransientKind.NETWORK),
            times = 2,
        )

        assertEquals(PinRelease.FAILED, service.releasePinnedCopy(ref.value))
        assertEquals(true, metaOf(ref)?.pinned)
    }

    @Test
    fun aBackupInProgressMakesTheReleaseWaitForTheNextTry() = runBlocking {
        val ref = put(pinned = true)
        lock.lock()
        try {
            assertEquals(PinRelease.FAILED, service.releasePinnedCopy(ref.value))
        } finally {
            lock.unlock()
        }
        assertEquals(true, metaOf(ref)?.pinned)
        // Sin la copia en curso, el reintento libera.
        assertEquals(PinRelease.RELEASED, service.releasePinnedCopy(ref.value))
    }

    @Test
    fun withoutAConnectedWebDavThereIsNothingToRelease() = runBlocking {
        val ref = put(pinned = true)
        row = row(kind = BackupDestinationKind.DOCUMENT_SLOTS.name)
        assertEquals(PinRelease.FAILED, service.releasePinnedCopy(ref.value))

        row = row(link = BackupLinkState.NOT_CONNECTED.name)
        assertEquals(PinRelease.FAILED, service.releasePinnedCopy(ref.value))

        row = null
        assertEquals(PinRelease.FAILED, service.releasePinnedCopy(ref.value))

        row = row()
        opened = DestinationOpen.PermissionLost
        assertEquals(PinRelease.FAILED, service.releasePinnedCopy(ref.value))

        assertEquals(PinRelease.FAILED, service.releasePinnedCopy(""))
        assertEquals(true, metaOf(ref)?.pinned)
        assertEquals(false, lock.isLocked)
    }

    @Test
    fun theMonthUsageStartsAgainInAnotherMonth() = runBlocking {
        assertEquals(0L, service.uploadedThisMonth())

        usageFile.writeText("""{"month":"2026-11","bytes":84000000}""")
        assertEquals(84_000_000L, service.uploadedThisMonth())

        month = "2026-12"
        assertEquals(0L, service.uploadedThisMonth())

        // Sin la zona del negocio no se inventa un mes.
        month = null
        assertEquals(0L, service.uploadedThisMonth())
    }

    @Test
    fun anUnreadableUsageFileCountsAsZero() = runBlocking {
        for (text in listOf(
            "",
            "{",
            """{"month":"2026-11"}""",
            """{"month":"2026-11","bytes":-5}""",
            """{"month":"2026-11","bytes":"5"}""",
            """{"month":"2026-13","bytes":5}""",
            """{"month":"2026-11","bytes":5,"other":1}""",
            """{"month":"2026-11","bytes":05}""",
            "x".repeat(200),
        )) {
            usageFile.writeText(text)
            assertEquals(text, 0L, service.uploadedThisMonth())
        }
    }

    @Test
    fun copiesAreEncryptedOnlyWhenEncryptionIsActive() = runBlocking {
        assertEquals(false, service.copiesEncrypted())

        val active = LocalEncryption.NONE.copy(active = true, keyAvailable = true)
        val encrypted =
            BackupSettingsService(
                state = { row },
                destinations = { opened },
                usage = UploadUsageStore({ usageFile }, Dispatchers.Unconfined),
                month = { month },
                backupLock = lock,
                rescheduled = {},
                encryption = { active },
            )
        assertEquals(true, encrypted.copiesEncrypted())
    }

    @Test
    fun aNewPolicySchedulesTheUploadAgain() = runBlocking {
        service.onMeteredPolicyChanged()

        assertEquals(1, rescheduled)
    }

    private fun put(pinned: Boolean): CopyRef {
        val at = requireNotNull(CopyInstant.parse("20261030T200200Z"))
        val name = BackupFileName(7, 1, at, OWN.take(8), BackupFileFormat.GZIP)
        destination.putFile(name.fileName, ByteArray(64))
        val meta =
            SlMeta(
                format = 1,
                keyId = null,
                deviceId = OWN,
                deviceLabel = null,
                generation = 7,
                schemaVersion = 1,
                appVersion = null,
                sha256 = "ab".repeat(32),
                rawSize = 4_096,
                fileSize = 64,
                records = 4_650,
                catalogCount = 3,
                valid = true,
            )
        val encoded = SlMetaCodec.encode(meta)
        destination.putFile(
            name.metaFileName,
            if (pinned) {
                requireNotNull(
                    SlMetaCodec.withPinned(encoded, name.identity(), true),
                )
            } else {
                encoded
            },
        )
        return CopyRef(name.fileName)
    }

    private fun metaOf(ref: CopyRef): SlMeta? {
        val name = requireNotNull(BackupFileName.parse(ref.value))
        val bytes = destination.file(name.metaFileName)
        return (SlMetaCodec.decode(bytes, name.identity()) as? CopyMetadata.Valid)?.meta
    }

    private fun row(
        kind: String = BackupDestinationKind.WEBDAV.name,
        link: String = BackupLinkState.CONNECTED.name,
    ) = BackupStateEntity(
        id = 1,
        changeSeq = 0,
        verifiedSeq = 0,
        firstUnsyncedAt = null,
        linkState = link,
        failureCode = null,
        consecutiveFailures = 0,
        lastFailureKind = null,
        invariantFailures = 0,
        lastAttemptAt = null,
        lastSuccessAt = null,
        lastUploadFinishedAt = null,
        lastStaleNoticeAt = null,
        lastDrillAt = null,
        lastDrillResult = null,
        lastFullVerifyAt = null,
        lastHumanCheckAt = null,
        humanCheckGeneration = null,
        destinationKind = kind,
        destinationAccount = null,
        destinationId = "destino-de-prueba",
        destinationFolder = "https://dav.example.com/app/",
        headGeneration = 0,
        headDeviceId = null,
        headFileRef = null,
        shrinkAcceptedGeneration = null,
        pendingGeneration = null,
        pendingSeq = null,
        pendingSha256 = null,
        pendingSizeBytes = null,
        conflictKind = null,
        conflictGeneration = null,
        conflictDeviceId = null,
        conflictFileRef = null,
        copyGeneration = null,
        copyDeviceId = null,
        copyCreatedAt = null,
    )

    private companion object {
        const val OWN = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"
    }
}
