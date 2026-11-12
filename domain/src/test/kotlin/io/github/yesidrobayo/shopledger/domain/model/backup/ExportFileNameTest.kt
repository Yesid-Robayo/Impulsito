package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** El nombre sugerido de una copia exportada lleva la hora del negocio, sin traducir. */
class ExportFileNameTest {
    private val at = Instant.parse("2026-11-12T03:07:00Z")

    @Test
    fun `the name carries the business date and time`() {
        // Cinco horas antes de UTC, la madrugada del 12 sigue siendo el 11.
        assertEquals(
            "shopledger-20261111-2207.db.gz",
            ExportFileName.of(at, BusinessTimeZone.of("Etc/GMT+5")),
        )
    }

    @Test
    fun `without a business zone the name goes in UTC`() {
        assertEquals("shopledger-20261112-0307.db.gz", ExportFileName.of(at, null))
    }
}
