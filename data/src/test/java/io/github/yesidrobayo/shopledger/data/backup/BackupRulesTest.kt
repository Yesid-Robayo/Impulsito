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
 * Las reglas del motor de respaldo que no necesitan la base: la guarda que decide si se sube, qué
 * copias del destino cuentan para ella, qué destino está conectado, los conteos neutros y que los
 * códigos que el motor guarda son los que admite la tabla.
 */
class BackupRulesTest {
    @Test
    fun `an empty destination or an older one uploads above everything this base wrote`() {
        assertEquals(GuardDecision.Upload(1), decide())
        assertEquals(GuardDecision.Upload(8), decide(head = 7, headDevice = OWN))
        assertEquals(
            GuardDecision.Upload(8),
            decide(listOf(copy(5, OTHER), copy(6, OWN)), head = 7, headDevice = OWN),
        )
        // La generación nueva salta por encima de una subida que no se confirmó.
        assertEquals(GuardDecision.Upload(10), decide(head = 7, headDevice = OWN, pending = 9))
    }

    @Test
    fun `the head of this base uploads the next one`() {
        assertEquals(
            GuardDecision.Upload(8),
            decide(listOf(copy(7, OWN), copy(3, OTHER)), head = 7, headDevice = OWN),
        )
        // La cabeza la puede haber escrito otro teléfono, si esta base la adoptó al restaurar.
        assertEquals(
            GuardDecision.Upload(8),
            decide(listOf(copy(7, OTHER)), head = 7, headDevice = OTHER),
        )
    }

    @Test
    fun `anything newer, foreign or from a newer app holds`() {
        // Más nueva que la cabeza, propia o ajena.
        assertEquals(GuardDecision.Hold, decide(listOf(copy(8, OWN)), head = 7, headDevice = OWN))
        assertEquals(GuardDecision.Hold, decide(listOf(copy(8, OTHER)), head = 7, headDevice = OWN))
        assertEquals(GuardDecision.Hold, decide(listOf(copy(1, OTHER))))
        // La misma generación, con una copia de otro teléfono: un empate siempre detiene.
        assertEquals(
            GuardDecision.Hold,
            decide(listOf(copy(7, OWN), copy(7, OTHER)), head = 7, headDevice = OWN),
        )
        // Una cabeza de una versión más nueva de la app, aunque sea más vieja que la de la base.
        assertEquals(
            GuardDecision.Hold,
            decide(listOf(copy(3, OTHER, schema = APP_SCHEMA + 1L)), head = 7, headDevice = OWN),
        )
    }

    @Test
    fun `the own unconfirmed upload is not a newer head`() {
        assertEquals(
            GuardDecision.Upload(9),
            decide(listOf(copy(7, OWN), copy(8, OWN)), head = 7, headDevice = OWN, pending = 8),
        )
        // La misma generación de otro teléfono sí cuenta.
        assertEquals(
            GuardDecision.Hold,
            decide(listOf(copy(8, OTHER)), head = 7, headDevice = OWN, pending = 8),
        )
    }

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
        assertEquals(listOf(copy(4, OWN), copy(4, OWN)), known)
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

    private fun decide(
        copies: List<KnownCopy> = emptyList(),
        head: Long = 0,
        headDevice: String? = null,
        pending: Long? = null,
    ): GuardDecision =
        HeadOrderGuard.decide(GuardInput(copies, OWN, head, headDevice, pending, APP_SCHEMA))

    private fun copy(generation: Long, device: String, schema: Long = APP_SCHEMA.toLong()) =
        KnownCopy(generation, device, schema)

    private fun nameOf(copy: KnownCopy) = BackupFileName(
        copy.generation,
        copy.schemaVersion.toInt(),
        requireNotNull(CopyInstant.parse("20261107T120000Z")),
        copy.deviceId.take(8),
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
        const val OTHER = "0c4d2e7a-91b3-4f5e-a6d7-8899aabbccdd"
        const val APP_SCHEMA = 1
    }
}
