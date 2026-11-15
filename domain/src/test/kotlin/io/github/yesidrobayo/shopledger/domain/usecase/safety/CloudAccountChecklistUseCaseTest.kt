package io.github.yesidrobayo.shopledger.domain.usecase.safety

import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountKind
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountMarks
import io.github.yesidrobayo.shopledger.domain.port.CloudAccountChecklistPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La lista de la cuenta: sin nada guardado no hay marcas, marcar y desmarcar guardan la lista
 * entera, las marcas de un tipo de destino no valen para el otro, y una marca que no se guardó
 * deja la lista como estaba.
 */
class CloudAccountChecklistUseCaseTest {
    private var saved: CloudAccountMarks? = null
    private var saves = true

    private val port =
        object : CloudAccountChecklistPort {
            override suspend fun load(): CloudAccountMarks? = saved

            override suspend fun save(marks: CloudAccountMarks): Boolean {
                if (saves) saved = marks
                return saves
            }
        }

    private val checklist = CloudAccountChecklistUseCase(port)

    @Test
    fun `without anything saved nothing is checked`() = runTest {
        assertEquals(emptySet<CloudAccountCheck>(), checklist.checked(CloudAccountKind.WEBDAV))
    }

    @Test
    fun `checking and unchecking save the whole list for that destination`() = runTest {
        val kind = CloudAccountKind.WEBDAV

        assertEquals(
            setOf(CloudAccountCheck.TWO_STEP),
            checklist.mark(kind, CloudAccountCheck.TWO_STEP, marked = true),
        )
        assertEquals(
            setOf(CloudAccountCheck.TWO_STEP, CloudAccountCheck.RECOVERY_PHONE),
            checklist.mark(kind, CloudAccountCheck.RECOVERY_PHONE, marked = true),
        )
        assertEquals(
            setOf(CloudAccountCheck.RECOVERY_PHONE),
            checklist.mark(kind, CloudAccountCheck.TWO_STEP, marked = false),
        )
        assertEquals(CloudAccountMarks(kind, setOf(CloudAccountCheck.RECOVERY_PHONE)), saved)
        assertEquals(setOf(CloudAccountCheck.RECOVERY_PHONE), checklist.checked(kind))
    }

    @Test
    fun `marks made for one destination do not count for the other`() = runTest {
        saved =
            CloudAccountMarks(
                CloudAccountKind.WEBDAV,
                setOf(CloudAccountCheck.TWO_STEP, CloudAccountCheck.RECOVERY_EMAIL),
            )

        assertEquals(
            emptySet<CloudAccountCheck>(),
            checklist.checked(CloudAccountKind.DOCUMENT_SLOTS),
        )
        val next =
            checklist.mark(
                CloudAccountKind.DOCUMENT_SLOTS,
                CloudAccountCheck.RECOVERY_EMAIL,
                marked = true,
            )

        assertEquals(setOf(CloudAccountCheck.RECOVERY_EMAIL), next)
        assertEquals(
            CloudAccountMarks(
                CloudAccountKind.DOCUMENT_SLOTS,
                setOf(CloudAccountCheck.RECOVERY_EMAIL),
            ),
            saved,
        )
    }

    @Test
    fun `a mark that was not saved leaves the list as it was`() = runTest {
        saved = CloudAccountMarks(CloudAccountKind.WEBDAV, setOf(CloudAccountCheck.TWO_STEP))
        saves = false

        assertNull(checklist.mark(CloudAccountKind.WEBDAV, CloudAccountCheck.TWO_STEP, false))
        assertEquals(setOf(CloudAccountCheck.TWO_STEP), checklist.checked(CloudAccountKind.WEBDAV))
    }
}
