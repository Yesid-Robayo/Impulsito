package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las reglas del motor de respaldo que no necesitan la base: qué copias del destino cuentan para la
 * guarda, qué destino está conectado, los conteos neutros y que los códigos que el motor guarda son
 * los que admite la tabla. La guarda tiene sus propias pruebas.
 */
class BackupRulesTest {
    @Test
    fun `only copies with valid metadata of their own name and size count`() {
        val good = listed(copy(4, OWN))
        val noMeta = good.copy(meta = ListedMeta.Missing)
        val invalid = good.copy(meta = ListedMeta.Invalid)
        val wrongSize = good.copy(sizeBytes = good.sizeBytes + 1)
        val otherName = good.copy(name = nameOf(copy(5, OWN)))
        val garbage = good.copy(meta = ListedMeta.Present("{}".encodeToByteArray()))
        val slot = good.copy(ref = CopyRef("A"), name = null)

        val known =
            knownCopies(
                CopyListing(
                    listOf(good, noMeta, invalid, wrongSize, otherName, garbage, slot),
                    emptyList(),
                    emptyList(),
                    null,
                ),
            )

        // La de la ranura no tiene nombre: sus metadatos se leen solos.
        assertEquals(
            listOf(copy(4, OWN), copy(4, OWN).copy(ref = CopyRef("A"))),
            known,
        )
    }

    @Test
    fun `only a connected link with a known destination is a destination to upload to`() {
        val connected =
            state(
                link = BackupLinkState.CONNECTED.name,
                kind = BackupDestinationKind.WEBDAV.name,
                id = "huella",
                folder = "https://dav.example.com/app/",
            )
        assertEquals(
            ConnectedDestination(
                BackupDestinationKind.WEBDAV,
                "huella",
                "https://dav.example.com/app/",
            ),
            connectedOf(connected),
        )
        assertEquals(
            ConnectedDestination(BackupDestinationKind.DOCUMENT_SLOTS, "huella", null),
            connectedOf(
                connected.copy(destinationKind = "DOCUMENT_SLOTS", destinationFolder = null),
            ),
        )
        for (link in BackupLinkState.entries - BackupLinkState.CONNECTED) {
            assertNull(link.name, connectedOf(connected.copy(linkState = link.name)))
        }
        assertNull(connectedOf(connected.copy(destinationKind = null)))
        assertNull(connectedOf(connected.copy(destinationKind = "OTRO")))
        assertNull(connectedOf(connected.copy(destinationId = null)))
        // La carpeta lleva la dirección del servicio: no va en el texto que puede ir a un registro.
        assertEquals("ConnectedDestination(WEBDAV)", connectedOf(connected).toString())
    }

    @Test
    fun `the neutral counts read exactly their tables`() {
        assertEquals(NeutralCounts.RECORD_TABLES, tablesOf(NeutralCounts.RECORD_COUNT))
        assertEquals(NeutralCounts.CATALOG_TABLES, tablesOf(NeutralCounts.CATALOG_COUNT))
        assertEquals(NeutralCounts.CONTENT_TABLES, tablesOf(NeutralCounts.IS_EMPTY))
    }

    @Test
    fun `the codes the engine keeps are the ones the table accepts`() {
        val guard = SchemaGuards.named("guard_backup_state_update").catalogSql
        assertEquals(BackupFailureCode.entries.map { it.name }, valuesOf(guard, "failure_code"))
        assertEquals(BackupLinkState.entries.map { it.name }, valuesOf(guard, "link_state"))
        assertEquals(BackupConflictKind.entries.map { it.name }, valuesOf(guard, "conflict_kind"))
    }

    private fun tablesOf(sql: String): List<String> =
        Regex("""FROM (\w+)""").findAll(sql).map { it.groupValues[1] }.toList()

    private fun valuesOf(guard: String, column: String): List<String> {
        val list = Regex("""NEW\.$column IN \(([^)]*)\)""").find(guard)
        return requireNotNull(list) { "La guarda no limita $column" }
            .groupValues[1]
            .split(",")
            .map { it.trim().removeSurrounding("'") }
    }

    private fun copy(generation: Long, device: String, schema: Long = APP_SCHEMA.toLong()) =
        KnownCopy(
            ref = CopyRef(nameOf(generation, device, schema).fileName),
            generation = generation,
            deviceId = device,
            schemaVersion = schema,
            format = 1,
            records = 10,
            catalogCount = 3,
        )

    private fun nameOf(copy: KnownCopy) = nameOf(copy.generation, copy.deviceId, copy.schemaVersion)

    private fun nameOf(generation: Long, device: String, schema: Long) = BackupFileName(
        generation,
        schema.toInt(),
        requireNotNull(CopyInstant.parse("20261107T120000Z")),
        device.take(8),
        BackupFileFormat.GZIP,
    )

    private fun listed(copy: KnownCopy): ListedCopy {
        val name = nameOf(copy)
        val meta =
            SlMeta(
                format = 1,
                keyId = null,
                deviceId = copy.deviceId,
                deviceLabel = null,
                generation = copy.generation,
                schemaVersion = copy.schemaVersion,
                appVersion = null,
                sha256 = "ab".repeat(32),
                rawSize = 4_096,
                fileSize = 1_234,
                records = 10,
                catalogCount = 3,
                valid = true,
            )
        return ListedCopy(
            CopyRef(name.fileName),
            name,
            1_234,
            ListedMeta.Present(SlMetaCodec.encode(meta)),
        )
    }

    private fun state(link: String, kind: String?, id: String?, folder: String?) =
        BackupStateEntity(
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
            destinationId = id,
            destinationFolder = folder,
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
        const val APP_SCHEMA = 1
    }
}
