package io.github.yesidrobayo.shopledger.domain.port

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageFailureTest {
    @Test
    fun `only a full disk is fixed by freeing space`() {
        assertEquals(
            listOf(StorageFailure.DISK_FULL),
            StorageFailure.entries.filter { it.isOutOfSpace },
        )
    }
}
