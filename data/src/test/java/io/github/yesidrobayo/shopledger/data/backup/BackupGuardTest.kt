package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.TemporaryFile
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La guarda que decide si una copia nueva puede subir, regla por regla y en su orden, con la tabla
 * de decisión de los documentos de respaldo como ejemplos.
 */
class BackupGuardTest {
    @Test
    fun `an empty database never uploads, whatever the destination has`() {
        val empty = LOCAL.copy(isEmpty = true)
        assertEquals(GuardDecision.Empty, decide(local = empty))
        assertEquals(GuardDecision.Empty, decide(listOf(copy(9, OTHER)), local = empty))
        // Ni siquiera una cabeza de una app más nueva cambia eso.
        assertEquals(
            GuardDecision.Empty,
            decide(listOf(copy(9, OTHER, schema = APP + 1L)), local = empty),
        )
    }

    @Test
    fun `a head from a newer schema stops with the code to update the app`() {
        val newer = BackupGuard.decide(input(listOf(copy(3, OTHER, schema = APP + 1L)), head = 7))
        assertEquals(GuardDecision.Refused(BackupFailureCode.CLOUD_SCHEMA_NEWER), newer)
        // Basta con una copia de la cabeza, y la regla va antes que el conflicto.
        assertEquals(
            GuardDecision.Refused(BackupFailureCode.CLOUD_SCHEMA_NEWER),
            decide(listOf(copy(9, OWN), copy(9, OTHER, schema = APP + 1L)), head = 7),
        )
        // Una copia vieja de un esquema más nuevo no es la cabeza: no detiene nada.
        assertEquals(
            GuardDecision.Upload(10),
            decide(listOf(copy(9, OWN), copy(3, OTHER, schema = APP + 1L)), head = 9),
        )
    }

    @Test
    fun `an encrypted head without the code never uploads in the clear`() {
        val encrypted = listOf(copy(7, OWN, format = 2))
        assertEquals(
            GuardDecision.Refused(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE),
            decide(encrypted, head = 7),
        )
        // Con el código en el teléfono, o tras apagar el cifrado a sabiendas, sube.
        val withCode = LocalEncryption.NONE.copy(hasRecoveryCode = true)
        val turnedOff = LocalEncryption.NONE.copy(offConfirmed = true)
        assertEquals(GuardDecision.Upload(8), decide(encrypted, head = 7, encryption = withCode))
        assertEquals(GuardDecision.Upload(8), decide(encrypted, head = 7, encryption = turnedOff))
        // Con el cifrado activo y la clave que no abre, tampoco sube, ni sin copias.
        val noKey = LocalEncryption.NONE.copy(active = true, hasRecoveryCode = true)
        assertEquals(
            GuardDecision.Refused(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE),
            decide(encryption = noKey),
        )
        assertEquals(GuardDecision.Upload(1), decide(encryption = noKey.copy(keyAvailable = true)))
    }

    @Test
    fun `without a head the copy goes above what this base confirmed`() {
        assertEquals(GuardDecision.Upload(1), decide())
        // La persona borró todas las copias o la carpeta.
        assertEquals(GuardDecision.Upload(11), decide(head = 10))
    }

    @Test
    fun `a newer head of this phone is an old base that came back`() {
        val decision = decide(listOf(copy(8, OWN), copy(7, OWN)), head = 7)
        assertEquals(GuardDecision.Conflict(BackupConflictKind.CLOUD_AHEAD, copy(8, OWN)), decision)
    }

    @Test
    fun `a newer head of another phone, or any head on a fresh start, is another phone`() {
        // Segundo teléfono con datos propios, o una instalación nueva que empezó de cero.
        assertEquals(
            GuardDecision.Conflict(BackupConflictKind.OTHER_DEVICE, copy(40, OTHER)),
            decide(listOf(copy(40, OTHER), copy(39, OTHER))),
        )
        // Un teléfono viejo que reaparece frente a la cabeza del nuevo.
        assertEquals(
            GuardDecision.Conflict(BackupConflictKind.OTHER_DEVICE, copy(12, OTHER)),
            decide(listOf(copy(12, OTHER), copy(10, OWN)), head = 10),
        )
        // Con la cabeza mezclada, la copia que se muestra es la del otro teléfono.
        assertEquals(
            GuardDecision.Conflict(BackupConflictKind.OTHER_DEVICE, copy(12, OTHER)),
            decide(listOf(copy(12, OWN), copy(12, OTHER)), head = 10),
        )
    }

    @Test
    fun `a tie with another phone is always another phone, in any order of the listing`() {
        val copies = listOf(copy(43, OWN), copy(43, OTHER), copy(42, OWN), copy(43, THIRD))
        val expected = BackupGuard.decide(input(copies, head = 43, headDevice = OWN))
        assertEquals(BackupConflictKind.OTHER_DEVICE, (expected as GuardDecision.Conflict).kind)
        val random = Random(43)
        repeat(50) {
            assertEquals(
                expected,
                BackupGuard.decide(input(copies.shuffled(random), head = 43, headDevice = OWN)),
            )
        }
        // También cuando la cabeza que conoce la base es la del otro teléfono.
        assertEquals(
            BackupConflictKind.OTHER_DEVICE,
            (decide(copies, head = 43, headDevice = OTHER) as GuardDecision.Conflict).kind,
        )
    }

    @Test
    fun `an older head means the person deleted copies, and nothing is overwritten`() {
        assertEquals(
            GuardDecision.Upload(11),
            decide(listOf(copy(7, OWN), copy(3, OTHER)), head = 10),
        )
    }

    @Test
    fun `the same head uploads the next one, also when another phone wrote it before`() {
        assertEquals(
            GuardDecision.Upload(8),
            decide(listOf(copy(7, OWN), copy(3, OTHER)), head = 7),
        )
        // La cabeza de otro teléfono, adoptada al restaurar.
        assertEquals(
            GuardDecision.Upload(8),
            decide(listOf(copy(7, OTHER)), head = 7, headDevice = OTHER),
        )
    }

    @Test
    fun `fewer records or less catalog than the same head is a shrunk base`() {
        val head = listOf(copy(7, OWN, records = 1_000, catalog = 500))
        assertEquals(
            GuardDecision.Conflict(BackupConflictKind.LOCAL_SHRUNK, head.single()),
            decide(head, head = 7, local = LOCAL.copy(records = 999, catalogCount = 500)),
        )
        // Un catálogo de 500 productos sin movimientos frente a una base que lo perdió.
        assertEquals(
            GuardDecision.Conflict(BackupConflictKind.LOCAL_SHRUNK, head.single()),
            decide(head, head = 7, local = LOCAL.copy(records = 1_000, catalogCount = 470)),
        )
        // Borrar productos a propósito cuenta cada uno como cambio, y no es una pérdida.
        assertEquals(
            GuardDecision.Upload(8),
            decide(
                head,
                head = 7,
                local = LOCAL.copy(records = 1_000, catalogCount = 470, pendingChanges = 30),
            ),
        )
        assertEquals(
            GuardDecision.Upload(8),
            decide(head, head = 7, local = LOCAL.copy(records = 1_200, catalogCount = 501)),
        )
    }

    @Test
    fun `a head the person accepted to keep below no longer stops the copies`() {
        val head = listOf(copy(7, OTHER, records = 1_000))
        val fewer = LOCAL.copy(records = 10)
        assertEquals(
            GuardDecision.Upload(8),
            decide(head, head = 7, headDevice = OTHER, local = fewer, accepted = 7),
        )
        // Aceptar otra cabeza no sirve para esta.
        assertEquals(
            BackupConflictKind.LOCAL_SHRUNK,
            (
                decide(head, head = 7, headDevice = OTHER, local = fewer, accepted = 6)
                    as GuardDecision.Conflict
                ).kind,
        )
        // Tras quedarse con este teléfono en un empate, el empate ya no detiene la copia.
        val tie = listOf(copy(43, OWN), copy(43, OTHER))
        assertEquals(
            GuardDecision.Upload(44),
            decide(tie, head = 43, headDevice = OTHER, accepted = 43),
        )
        // Pero una cabeza más nueva que la aceptada sí.
        assertEquals(
            BackupConflictKind.OTHER_DEVICE,
            (
                decide(tie + copy(44, OTHER), head = 43, headDevice = OTHER, accepted = 43)
                    as GuardDecision.Conflict
                ).kind,
        )
    }

    @Test
    fun `the own unconfirmed upload and rejected generations are not a head`() {
        assertEquals(
            GuardDecision.Upload(9),
            decide(listOf(copy(7, OWN), copy(8, OWN)), head = 7, pending = 8),
        )
        assertEquals(
            GuardDecision.Upload(10),
            decide(listOf(copy(7, OWN), copy(8, OWN)), head = 7, rejected = setOf(8, 9)),
        )
        // La misma generación de otro teléfono sí cuenta.
        assertEquals(
            BackupConflictKind.OTHER_DEVICE,
            (decide(listOf(copy(8, OTHER)), head = 7, pending = 8) as GuardDecision.Conflict).kind,
        )
        assertEquals(
            BackupConflictKind.OTHER_DEVICE,
            (
                decide(
                    listOf(copy(8, OTHER)),
                    head = 7,
                    rejected = setOf(8),
                ) as GuardDecision.Conflict
                )
                .kind,
        )
    }

    @Test
    fun `the new generation never repeats a name this phone left in the destination`() {
        // Una copia propia sin metadatos, o con otro tamaño, no cuenta, pero su nombre está.
        assertEquals(GuardDecision.Upload(13), decide(head = 7, highestOwn = 12))
        assertEquals(
            GuardDecision.Upload(13),
            decide(listOf(copy(7, OWN)), head = 7, highestOwn = 12),
        )
        // Ni una rechazada que todavía no se pudo borrar.
        assertEquals(
            GuardDecision.Upload(14),
            decide(listOf(copy(7, OWN)), head = 7, highestOwn = 12, rejected = setOf(13)),
        )
    }

    @Test
    fun `the highest own generation comes from every name of this phone`() {
        fun name(generation: Long, device: String) = BackupFileName(
            generation,
            APP,
            requireNotNull(CopyInstant.parse("20261107T120000Z")),
            device.take(8),
            BackupFileFormat.GZIP,
        )
        val listing =
            CopyListing(
                copies =
                    listOf(
                        ListedCopy(
                            CopyRef(name(5, OWN).fileName),
                            name(5, OWN),
                            10,
                            ListedMeta.Missing,
                        ),
                        ListedCopy(
                            CopyRef(name(30, OTHER).fileName),
                            name(30, OTHER),
                            10,
                            ListedMeta.Missing,
                        ),
                        ListedCopy(CopyRef("A"), null, 10, ListedMeta.Missing),
                    ),
                orphanMetas = listOf(CopyRef(name(8, OWN).metaFileName)),
                temporaries =
                    listOf(
                        TemporaryFile(
                            CopyRef(name(11, OWN).fileName + BackupFileName.TEMPORARY_SUFFIX),
                            OWN.take(8),
                        ),
                        TemporaryFile(CopyRef("ajeno.sltmp"), null),
                    ),
                quotaAvailableBytes = null,
            )
        assertEquals(11L, highestOwnGeneration(listing, OWN))
        assertEquals(30L, highestOwnGeneration(listing, OTHER))
        assertEquals(0L, highestOwnGeneration(listing, THIRD))
    }

    private fun decide(
        copies: List<KnownCopy> = emptyList(),
        head: Long = 0,
        headDevice: String? = if (head > 0) OWN else null,
        pending: Long? = null,
        local: LocalCounts = LOCAL,
        accepted: Long? = null,
        highestOwn: Long = 0,
        rejected: Set<Long> = emptySet(),
        encryption: LocalEncryption = LocalEncryption.NONE,
    ): GuardDecision = BackupGuard.decide(
        input(copies, head, headDevice, pending, local, accepted, highestOwn, rejected, encryption),
    )

    private fun input(
        copies: List<KnownCopy>,
        head: Long = 0,
        headDevice: String? = if (head > 0) OWN else null,
        pending: Long? = null,
        local: LocalCounts = LOCAL,
        accepted: Long? = null,
        highestOwn: Long = 0,
        rejected: Set<Long> = emptySet(),
        encryption: LocalEncryption = LocalEncryption.NONE,
    ) = GuardInput(
        copies = copies,
        deviceId = OWN,
        headGeneration = head,
        headDeviceId = headDevice,
        pendingGeneration = pending,
        appSchemaVersion = APP,
        local = local,
        shrinkAcceptedGeneration = accepted,
        highestOwnGeneration = highestOwn,
        rejectedGenerations = rejected,
        encryption = encryption,
    )

    private fun copy(
        generation: Long,
        device: String,
        schema: Long = APP.toLong(),
        format: Int = 1,
        records: Long = 100,
        catalog: Long = 20,
    ) = KnownCopy(
        ref = CopyRef("g$generation-${device.take(8)}"),
        generation = generation,
        deviceId = device,
        schemaVersion = schema,
        format = format,
        records = records,
        catalogCount = catalog,
    )

    private companion object {
        const val OWN = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"
        const val OTHER = "0c4d2e7a-91b3-4f5e-a6d7-8899aabbccdd"
        const val THIRD = "5b6c7d8e-1234-4abc-9def-001122334455"
        const val APP = 1

        /** Una base con más que cualquier cabeza de estas pruebas, salvo donde se dice. */
        val LOCAL =
            LocalCounts(isEmpty = false, records = 100, catalogCount = 20, pendingChanges = 1)
    }
}
