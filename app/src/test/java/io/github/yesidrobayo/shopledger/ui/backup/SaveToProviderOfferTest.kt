package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cuándo Inicio y la pantalla del respaldo ofrecen `Guardar en {provider}` como acción
 * principal: solo con la nube del teléfono, que no tiene copias automáticas, y solo con cambios
 * sin copia y un estado en que pedirla sirve. Datos inventados.
 */
class SaveToProviderOfferTest {
    private fun wording(
        status: BackupStatus,
        kind: BackupDestinationKind? = BackupDestinationKind.DOCUMENT_SLOTS,
        pending: Long = 3,
    ) = BackupWording(
        previewOverview(status, kind, pending),
        providerLabel = if (kind == BackupDestinationKind.DOCUMENT_SLOTS) "Nube" else null,
        zone = PREVIEW_BACKUP_ZONE,
    )

    @Test
    fun `with the phone cloud and changes it is offered where asking for a copy helps`() {
        val offered =
            BackupStatus.entries.filter { wording(it).offersSaveToProvider() }.toSet()

        assertEquals(
            setOf(
                BackupStatus.PENDING,
                BackupStatus.STALE,
                BackupStatus.FAILED,
                BackupStatus.CHECK_DUE,
            ),
            offered,
        )
    }

    @Test
    fun `without changes nothing is offered`() {
        for (status in BackupStatus.entries) {
            assertFalse("$status", wording(status, pending = 0).offersSaveToProvider())
        }
    }

    @Test
    fun `with WebDAV or without a destination it is never offered`() {
        for (kind in listOf(BackupDestinationKind.WEBDAV, null)) {
            for (status in BackupStatus.entries) {
                assertFalse("$kind $status", wording(status, kind).offersSaveToProvider())
            }
        }
    }
}
