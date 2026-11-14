package io.github.yesidrobayo.shopledger.domain.model.imports

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportApplyResultTest {
    @Test
    fun `only a full disk counts as out of space`() {
        assertTrue(ImportApplyResult.StorageFailed(StorageFailure.DISK_FULL).outOfSpace)
        StorageFailure.entries.filter { it != StorageFailure.DISK_FULL }.forEach {
            assertFalse(ImportApplyResult.StorageFailed(it).outOfSpace)
        }
    }
}
