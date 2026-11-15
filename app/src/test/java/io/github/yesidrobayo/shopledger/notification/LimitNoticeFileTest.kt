package io.github.yesidrobayo.shopledger.notification

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.LimitNoticeMark
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El último aviso del límite, guardado fuera de la base: se escribe y se lee igual, sin dejar el
 * temporal, y una línea que no es exactamente la que se escribe vale como ningún aviso.
 */
class LimitNoticeFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val mark = LimitNoticeMark(
        BusinessDate(LocalDate(2025, 12, 1)),
        Money(10_000_000),
        12,
        LimitLevel.FIRST_NOTICE,
    )

    @Test
    fun `what is written is read back the same and leaves no temporary`() = runBlocking {
        val store = LimitNoticeFile({ folder.root }, Dispatchers.Unconfined)

        assertNull(store.read())
        store.write(mark)

        assertEquals(mark, store.read())
        assertEquals(
            "1 2025-12-01 10000000 12 FIRST_NOTICE\n",
            File(folder.root, LimitNoticeFile.FILE_NAME).readText(),
        )
        assertFalse(File(folder.root, LimitNoticeFile.TEMPORARY_NAME).exists())
    }

    @Test
    fun `a line that is not exactly the written one counts as no notice`() {
        listOf(
            "",
            "2 2025-12-01 10000000 12 FIRST_NOTICE",
            "1 2025-13-01 10000000 12 FIRST_NOTICE",
            "1 2025-12-01 0 12 FIRST_NOTICE",
            "1 2025-12-01 10000000 0 FIRST_NOTICE",
            "1 2025-12-01 10000000 12 HALFWAY",
            "1 2025-12-01 10000000 12",
        ).forEach { assertNull(it, LimitNoticeFile.parse(it)) }
    }

    @Test
    fun `a file too large is not read`() = runBlocking {
        val target = File(folder.root, LimitNoticeFile.FILE_NAME)
        target.writeText("1 ".repeat(LimitNoticeFile.MAX_BYTES))

        assertNull(LimitNoticeFile({ folder.root }, Dispatchers.Unconfined).read())
    }
}
