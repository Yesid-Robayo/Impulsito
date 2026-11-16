package io.github.yesidrobayo.shopledger.realaccounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.realaccounts.RealAccounts.report
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Antes de todo: qué destino tiene conectado esta instalación, si la política está aceptada y si
 * el enlace está bien. Solo lee: no abre la credencial, no escribe nada y no usa la red.
 *
 * El guion del PC lee `kind=` de su línea para saber qué rama corre en este emulador, sin depender
 * del nombre del emulador. Con `-e expect webdav` o `-e expect slots` falla si el destino no es ese.
 */
@RunWith(AndroidJUnit4::class)
class RealPreflightTest {
    @Test
    fun describesTheConnectedDestination() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val ports = RealAccounts.ports()
        val facts = ports.backupStatus.current()
        val status = RealAccounts.visibleStatus()
        val policy = RealAccounts.app.container.data.policyConsent.status()
        val grants = RealAccounts.context.contentResolver.persistedUriPermissions
        val readWrite = grants.filter { it.isReadPermission && it.isWritePermission }
        val drive = readWrite.count { it.uri.authority == RealAccounts.DRIVE_AUTHORITY }
        report(
            "preflight",
            "kind" to facts.destinationKind,
            "link" to facts.linkState,
            "policy" to policy,
            "status" to status.status,
            "pending" to status.pendingChanges,
            "changeSeq" to facts.changeSeq,
            "verifiedSeq" to facts.verifiedSeq,
            "empty" to facts.databaseEmpty,
            "failure" to facts.failureCode,
            "bucket" to RealAccounts.standbyBucket(),
            "grants" to grants.size,
            "grantsReadWrite" to readWrite.size,
            "grantsDrive" to drive,
        )
        val kind = facts.destinationKind
        assertNotNull("No hay destino conectado", kind)
        assertEquals(BackupLinkState.CONNECTED, facts.linkState)
        assertEquals(PolicyConsentStatus.ACCEPTED, policy)
        when (RealAccounts.argument("expect", "any")) {
            "webdav" -> assertEquals(BackupDestinationKind.WEBDAV, kind)
            "slots" -> assertEquals(BackupDestinationKind.DOCUMENT_SLOTS, kind)
        }
        if (kind == BackupDestinationKind.DOCUMENT_SLOTS) {
            // Solo los tres documentos que la persona creó en el selector, y todos de Drive.
            assertEquals(SLOTS, readWrite.size)
            assertEquals(SLOTS, drive)
            assertEquals(SLOTS, readWrite.map { it.uri }.distinct().size)
        }
    }

    private companion object {
        const val SLOTS = 3
    }
}
