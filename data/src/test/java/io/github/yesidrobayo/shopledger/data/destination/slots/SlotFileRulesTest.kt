package io.github.yesidrobayo.shopledger.data.destination.slots

import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Qué nube y qué documento sirven para una ranura nueva. */
class SlotFileRulesTest {
    @Test
    fun `only Drive is approved today, with its own label`() {
        assertEquals(listOf("Drive"), SLOT_PROVIDERS.map { it.label })
        assertEquals("Drive", slotProviderOf("com.google.android.apps.docs.storage")?.label)
    }

    @Test
    fun `a folder of the phone, another cloud or no authority is not approved`() {
        assertNull(slotProviderOf("com.android.externalstorage.documents"))
        assertNull(slotProviderOf("com.android.providers.downloads.documents"))
        assertNull(slotProviderOf("com.example.othercloud.documents"))
        assertNull(slotProviderOf(null))
    }

    @Test
    fun `an empty writable file with a known size is accepted`() {
        assertNull(
            rejectionOf(PickedDocument("a", writable = true, sizeBytes = 0, displayName = "x")),
        )
    }

    @Test
    fun `each reason to reject a new file`() {
        assertEquals(SlotFileRejection.NOT_WRITABLE, rejectionOf(document(writable = false)))
        assertEquals(SlotFileRejection.SIZE_UNKNOWN, rejectionOf(document(size = null)))
        assertEquals(SlotFileRejection.NOT_EMPTY, rejectionOf(document(size = 1)))
        // Un tamaño negativo tampoco prueba que esté vacío.
        assertEquals(SlotFileRejection.NOT_EMPTY, rejectionOf(document(size = -1)))
    }

    @Test
    fun `a file that cannot be written is rejected before looking at its size`() {
        assertEquals(
            SlotFileRejection.NOT_WRITABLE,
            rejectionOf(document(writable = false, size = null)),
        )
    }

    @Test
    fun `the name chosen by the person never reaches a log line`() {
        assertFalse(document(name = "invented-name").toString().contains("invented-name"))
        assertFalse(
            PendingSlotFile("content://x/y", "Drive", "invented").toString().contains("x/y"),
        )
    }

    private fun document(writable: Boolean = true, size: Long? = 0, name: String? = null) =
        PickedDocument("authority", writable, size, name)
}
