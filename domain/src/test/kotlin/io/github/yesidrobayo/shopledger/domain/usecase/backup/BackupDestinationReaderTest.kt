package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** El lector del destino solo pasa lo que dice el destino: no escribe ni inventa nada. */
class BackupDestinationReaderTest {
    private val source =
        object : BackupDestinationSource {
            override suspend fun describe(): BackupDestinationInfo? =
                BackupDestinationInfo.Slots("Nube")

            override suspend fun listCopies(): BackupCopyList = BackupCopyList.NoInternet

            override fun hasNetwork(): Boolean = false
        }

    @Test
    fun `it reads the destination, its copies and the network as they are`() = runTest {
        val reader = BackupDestinationReader(source)

        assertEquals(BackupDestinationInfo.Slots("Nube"), reader.describe())
        assertEquals(BackupCopyList.NoInternet, reader.listCopies())
        assertFalse(reader.hasNetwork())
    }
}
