package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CurrentDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.TestedWebDav
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conectar otro destino pregunta; reconectar el mismo, o conectar el primero, no. */
class ConnectWebDavUseCaseTest {
    private val tested = TestedWebDav("nuevo", "App", hasCopies = false)

    private fun current(id: String, kind: BackupDestinationKind) =
        CurrentDestination(kind, id, null, null, null)

    @Test
    fun `the first destination does not ask`() {
        assertFalse(ConnectWebDavUseCase.changesDestination(null, tested))
    }

    @Test
    fun `the same service and user is reconnecting`() {
        val same = current("nuevo", BackupDestinationKind.WEBDAV)
        assertFalse(ConnectWebDavUseCase.changesDestination(same, tested))
    }

    @Test
    fun `another service or the phone cloud is a change`() {
        val other = current("otro", BackupDestinationKind.WEBDAV)
        val slots = current("ranuras", BackupDestinationKind.DOCUMENT_SLOTS)
        assertTrue(ConnectWebDavUseCase.changesDestination(other, tested))
        assertTrue(ConnectWebDavUseCase.changesDestination(slots, tested))
    }
}
